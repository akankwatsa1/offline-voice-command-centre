#!/usr/bin/env node
/**
 * Offline validation of the tool schema and the frozen case suite.
 *
 * This runs without the model, so it is the fast gate: it catches a tool renamed in
 * tools.json but not in cases.json, an argument that does not exist, an enum value the
 * grammar would reject, and the two documented limits that are easy to trip —
 * more than five tools (which silently switches the engine to tool retrieval) and a
 * required argument that the request can never evidence.
 *
 *   node tools/validate-schema.mjs
 */
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const tools = JSON.parse(readFileSync(join(ROOT, "shared", "tools.json"), "utf8"));
const suite = JSON.parse(readFileSync(join(ROOT, "shared", "cases.json"), "utf8"));

const problems = [];
const warnings = [];
const fail = (m) => problems.push(m);
const warn = (m) => warnings.push(m);

// ---------------------------------------------------------------- tool schema

if (!Array.isArray(tools) || tools.length === 0) fail("shared/tools.json must be a non-empty array");

const byName = new Map();
for (const tool of tools) {
  if (!tool.name) {
    fail("a tool has no name");
    continue;
  }
  if (byName.has(tool.name)) fail(`duplicate tool name "${tool.name}"`);
  byName.set(tool.name, tool);

  if (!tool.description || !tool.description.trim()) {
    fail(`${tool.name}: needs a description, since the description is the whole contract`);
  }
  const params = tool.parameters;
  if (!params || params.type !== "object" || typeof params.properties !== "object") {
    fail(`${tool.name}: parameters must be an object schema with properties`);
    continue;
  }
  for (const [arg, spec] of Object.entries(params.properties)) {
    if (!spec.type) fail(`${tool.name}.${arg}: needs a type`);
    if (spec.type === "string" && spec.enum && !Array.isArray(spec.enum)) {
      fail(`${tool.name}.${arg}: enum must be an array`);
    }
    if (spec.enum && spec.enum.some((v) => typeof v !== "string")) {
      fail(`${tool.name}.${arg}: enum values must be strings for the decode grammar`);
    }
    // Needle does not infer polarity from prose: a boolean must follow the request verb.
    if (spec.type === "boolean") {
      warn(`${tool.name}.${arg}: booleans rely on the model inferring polarity; an enum of on/off is more reliable`);
    }
  }
  for (const required of params.required ?? []) {
    if (!params.properties[required]) {
      fail(`${tool.name}: required argument "${required}" is not declared in properties`);
    }
  }
}

// Five or fewer tools render directly. Above that the engine embeds every schema and
// puts only the five closest in the per-turn prefix, which makes an unselected tool
// unreachable rather than merely unlikely.
if (tools.length > 5) {
  warn(`${tools.length} tools declared: above five, the engine switches to tool retrieval and an unselected tool becomes unreachable`);
}
if (tools.length === 0) fail("no tools declared");

// ------------------------------------------------------------------- cases

const cats = Object.keys(suite.categories ?? {});
const seenIds = new Set();
const perCat = {};

for (const c of suite.cases ?? []) {
  if (!c.id) fail("a case has no id");
  if (seenIds.has(c.id)) fail(`duplicate case id "${c.id}"`);
  seenIds.add(c.id);

  if (!cats.includes(c.cat)) fail(`${c.id}: unknown category "${c.cat}"`);
  perCat[c.cat] = (perCat[c.cat] ?? 0) + 1;

  if (typeof c.input !== "string" || !c.input.trim()) fail(`${c.id}: needs an input string`);
  if (!c.expect || !Array.isArray(c.expect.calls)) {
    fail(`${c.id}: expect.calls must be an array (use [] for a refusal)`);
    continue;
  }

  if (c.expect.calls.length > 1 && c.cat !== "multi") {
    warn(`${c.id}: category "${c.cat}" has ${c.expect.calls.length} expected calls`);
  }

  for (const entry of c.expect.calls) {
    if (!Array.isArray(entry) || entry.length !== 2) {
      fail(`${c.id}: each expected call must be [name, arguments]`);
      continue;
    }
    const [name, args] = entry;
    const tool = byName.get(name);
    if (!tool) {
      fail(`${c.id}: expected call to unknown tool "${name}"`);
      continue;
    }
    const props = tool.parameters.properties;
    for (const [arg, value] of Object.entries(args)) {
      const spec = props[arg];
      if (!spec) {
        fail(`${c.id}: ${name} has no argument "${arg}"`);
        continue;
      }
      if (spec.enum && !spec.enum.includes(value)) {
        fail(`${c.id}: ${name}.${arg} = ${JSON.stringify(value)} is outside the grammar's enum [${spec.enum.join(", ")}]`);
      }
      if (spec.type === "integer" && !Number.isInteger(value)) {
        fail(`${c.id}: ${name}.${arg} must be an integer`);
      }
      if (spec.type === "string" && typeof value !== "string") {
        fail(`${c.id}: ${name}.${arg} must be a string`);
      }
    }
    for (const required of tool.parameters.required ?? []) {
      if (!(required in args)) {
        fail(`${c.id}: ${name} is missing required argument "${required}"`);
      }
    }
  }
}

// Every tool should be exercised by at least one positive case, or it is untested.
const exercised = new Set();
for (const c of suite.cases ?? []) {
  for (const entry of c.expect?.calls ?? []) exercised.add(entry[0]);
}
for (const name of byName.keys()) {
  if (!exercised.has(name)) warn(`tool "${name}" has no positive case, so its routing is untested`);
}

for (const cat of cats) {
  if (!perCat[cat]) warn(`category "${cat}" has no cases`);
}

// ------------------------------------------------------------------ report

if (warnings.length) {
  console.log("warnings");
  for (const w of warnings) console.log("  ! " + w);
  console.log("");
}

if (problems.length) {
  console.log("problems");
  for (const p of problems) console.log("  x " + p);
  console.log(`\n${problems.length} problem(s) found.`);
  process.exit(1);
}

const summary = Object.entries(perCat)
  .map(([k, v]) => `${k}=${v}`)
  .join(" ");
console.log(`ok: ${tools.length} tools, ${suite.cases.length} cases (${summary})`);
console.log(`tools: ${[...byName.keys()].join(", ")}`);

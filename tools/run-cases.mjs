#!/usr/bin/env node
/**
 * Run the frozen tool-routing suite in shared/cases.json against the real Needle 3 engine.
 *
 *   node tools/run-cases.mjs                       # auto-detects the host engine
 *   node tools/run-cases.mjs --serve               # one process, model loaded once (default when available)
 *   node tools/run-cases.mjs --oneshot             # one process per case, slower, more isolated
 *   node tools/run-cases.mjs --min-confidence 0.7  # also report the act/confirm/refuse split
 *
 * Exit code is non-zero if any case fails, so CI can gate the APK build on the schema
 * actually working rather than on it merely parsing.
 */
import { spawn, spawnSync } from "node:child_process";
import { readFileSync, writeFileSync, existsSync, chmodSync, mkdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const MODELS = join(ROOT, "models");
const PORT = 8399;

const args = process.argv.slice(2);
const has = (f) => args.includes(f);
const getFlag = (f) => {
  const i = args.indexOf(f);
  return i >= 0 ? args[i + 1] : undefined;
};
const MIN_CONF = parseFloat(getFlag("--min-confidence") ?? "0.7");
const FORCE_ONESHOT = has("--oneshot");
const FORCE_SERVE = has("--serve");

const PLATFORM_ORDER =
  process.platform === "win32"
    ? ["windows-x86_64", "windows-arm64"]
    : process.platform === "darwin"
      ? ["macos-arm64"]
      : ["linux-x86_64", "linux-arm64"];

function findEngine() {
  const explicit = getFlag("--engine");
  if (explicit) return resolve(explicit);
  for (const p of PLATFORM_ORDER) {
    for (const name of ["needle", "needle.exe"]) {
      const candidate = join(MODELS, p, name);
      if (existsSync(candidate)) return candidate;
    }
  }
  return null;
}

const engine = findEngine();
const weights = join(MODELS, "needle3.cact");
const toolsPath = join(ROOT, "shared", "tools.json");
const systemFile = writeSystemFile();

if (!engine) {
  console.error(`No Needle engine found under models/. Run first:\n  node scripts/fetch-models.mjs --platform ${PLATFORM_ORDER[0]}`);
  process.exit(2);
}
if (!existsSync(weights)) {
  console.error(`Missing ${weights}. Run:\n  node scripts/fetch-models.mjs --platform ${PLATFORM_ORDER[0]}`);
  process.exit(2);
}
try {
  if (process.platform !== "win32") chmodSync(engine, 0o755);
} catch {
  /* best effort */
}

const suite = JSON.parse(readFileSync(join(ROOT, "shared", "cases.json"), "utf8"));
const cases = suite.cases;

/** System facts, never instructions (llms.txt > System facts). */
function systemFacts() {
  const now = new Date();
  const days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  const p = (n) => String(n).padStart(2, "0");
  const date = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ${days[now.getDay()]} ${p(now.getHours())}:${p(now.getMinutes())}`;
  return `date: ${date}; locale: en-US; device: phone; network: wifi`;
}

const COMMON = ["--model", weights, "--tools", toolsPath, "--max", "200"];

/** The engine prints one JSON object; tolerate banners or trailing whitespace around it. */
function parseResponse(raw) {
  const text = String(raw ?? "").trim();
  if (!text) return null;
  const candidates = [];
  for (const line of text.split(/\r?\n/)) {
    const t = line.trim();
    if (t.startsWith("{") && t.endsWith("}")) candidates.push(t);
  }
  candidates.push(text);
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start >= 0 && end > start) candidates.push(text.slice(start, end + 1));
  for (const c of candidates.reverse()) {
    try {
      const obj = JSON.parse(c);
      if (obj && typeof obj === "object") return obj;
    } catch {
      /* keep trying */
    }
  }
  return null;
}

function oneshot(input) {
  const r = spawnSync(engine, [...COMMON, "--system", systemFile, "--prompt", input], {
    encoding: "utf8",
    timeout: 120000,
    maxBuffer: 32 * 1024 * 1024,
  });
  return { out: r.stdout, err: r.stderr, status: r.status };
}

/** The engine reads session facts from a file (`--system path`), never from a flag. */
function writeSystemFile() {
  mkdirSync(MODELS, { recursive: true });
  const file = join(MODELS, "system.txt");
  writeFileSync(file, systemFacts() + "\n");
  return file;
}

async function startServer() {
  // Deliberately no piped stdio: some sandboxes deny pipe creation outright, and the
  // engine's log is not needed here because every result arrives over loopback HTTP.
  // Ignoring stdout/stderr also means a long-running engine can never block on a full
  // pipe buffer, which a piped version would have to drain.
  const child = spawn(engine, [...COMMON, "--system", systemFile, "--serve", "--port", String(PORT)], {
    stdio: ["ignore", "ignore", "ignore"],
  });

  const deadline = Date.now() + 120000;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new Error(`engine exited during start-up with code ${child.exitCode}`);
    }
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/reset`, {
        method: "POST",
        signal: AbortSignal.timeout(2000),
      });
      if (r.ok || r.status === 404) return { child };
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  child.kill();
  throw new Error(`engine did not start serving on port ${PORT} within two minutes`);
}

async function serveComplete(input) {
  const r = await fetch(`http://127.0.0.1:${PORT}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ input }),
    signal: AbortSignal.timeout(120000),
  });
  return parseResponse(await r.text());
}

function normaliseArgs(a) {
  const out = {};
  for (const [k, v] of Object.entries(a ?? {})) {
    if (typeof v !== "string") {
      out[k] = v;
      continue;
    }
    let s = v.trim().toLowerCase();
    // The model sometimes copies the preposition with the time span ("at 5:00 PM") and
    // sometimes not ("5:00 pm"). Both are correct spans and TimePhrases.parse accepts
    // either, so compare the time itself rather than the exact wording.
    if (k === "when") s = s.replace(/^(at|for|by|around|about)\s+/, "");
    out[k] = s;
  }
  return out;
}

/** Order-insensitive comparison: a set of "name|sorted-args" keys. */
function callKey(call) {
  const entries = Object.entries(normaliseArgs(call.arguments)).sort(([a], [b]) => a.localeCompare(b));
  return `${call.name}|${JSON.stringify(entries)}`;
}

function compare(got, expected) {
  const g = (got ?? []).map(callKey).sort();
  const e = (expected ?? []).map(([name, a]) => callKey({ name, arguments: a })).sort();
  return { ok: JSON.stringify(g) === JSON.stringify(e), g, e };
}

async function main() {
  const useServe = !FORCE_ONESHOT;
  console.log(`engine   ${engine}`);
  console.log(`weights  ${weights} (${(readFileSync(weights).length / 1048576).toFixed(2)} MB)`);
  console.log(`mode     ${useServe ? "serve" : "oneshot"}${FORCE_SERVE ? " (forced)" : ""}`);
  console.log(`facts    ${systemFacts()}`);
  console.log(`cases    ${cases.length}\n`);

  let server = null;
  if (useServe) {
    try {
      server = await startServer();
    } catch (e) {
      console.log(`serve mode unavailable (${e.message}); falling back to oneshot\n`);
    }
  }

  const results = [];
  const byCat = {};
  let rows = "";

  for (const c of cases) {
    const t0 = Date.now();
    let resp = null;
    let err = null;
    try {
      if (server) {
        await fetch(`http://127.0.0.1:${PORT}/reset`, { method: "POST" }).catch(() => {});
        resp = await serveComplete(c.input);
      } else {
        const r = oneshot(c.input);
        resp = parseResponse(r.out);
        if (!resp) err = (r.err || r.out || "").slice(-300) || "no JSON in output";
      }
    } catch (e) {
      err = e.message;
    }
    const ms = Date.now() - t0;

    const calls = resp?.function_calls ?? [];
    const held = resp?.suppressed_calls ?? [];
    const conf = typeof resp?.confidence === "number" ? resp.confidence : null;
    const cmp = err ? { ok: false, g: [], e: [] } : compare(calls, c.expect.calls);

    const verdict = conf === null ? "?" : conf >= MIN_CONF ? "act" : conf >= 0.1 ? "confirm" : "refuse";
    const status = err ? "ERR " : cmp.ok ? "pass" : "FAIL";
    byCat[c.cat] ??= { pass: 0, total: 0 };
    byCat[c.cat].total++;
    if (cmp.ok && !err) byCat[c.cat].pass++;
    results.push({ id: c.id, cat: c.cat, ok: cmp.ok && !err, conf, verdict, called: calls.map((x) => x.name) });

    rows += `${status} ${c.id.padEnd(30)} conf=${String(conf ?? "-").padEnd(5)} ${verdict.padEnd(7)} ${String(ms).padStart(5)}ms  ${c.input}\n`;
    if (!cmp.ok) {
      rows += `       expected ${cmp.e.join("  +  ") || "[]"}\n`;
      rows += `       got      ${cmp.g.join("  +  ") || "[]"}${held.length ? `  (held: ${held.map((h) => h.name).join(", ")})` : ""}${err ? `  error: ${err}` : ""}\n`;
      if (resp?.reasoning) rows += `       reasoning: ${resp.reasoning}\n`;
    }
  }

  console.log(rows);
  console.log("by category");
  for (const [cat, v] of Object.entries(byCat)) {
    const mark = v.pass === v.total ? "ok  " : "FAIL";
    console.log(`  ${mark} ${cat.padEnd(18)} ${v.pass}/${v.total}`);
  }
  const passed = results.filter((r) => r.ok).length;
  console.log(`\n${passed}/${results.length} cases passed`);

  const confs = results.map((r) => r.conf).filter((c) => typeof c === "number");
  if (confs.length) {
    const below = results.filter((r) => r.ok && typeof r.conf === "number" && r.conf < MIN_CONF);
    console.log(`confidence: ${confs.length}/${results.length} scored, min ${Math.min(...confs).toFixed(2)}, ` +
      `below ${MIN_CONF} threshold: ${below.length}`);
    if (below.length) console.log(`  would need confirmation: ${below.map((r) => r.id).join(", ")}`);
  }
  const unscored = results.length - confs.length;
  if (unscored) console.log(`warning: ${unscored} case(s) returned no confidence score — the act/confirm gate cannot be tuned`);

  if (server) server.child.kill();

  if (passed !== results.length) {
    console.log(`\n${results.length - passed} case(s) failed.`);
    process.exit(1);
  }
  console.log("\nall cases passed");
}

main().catch((e) => {
  console.error(`\nrun-cases failed: ${e.message}`);
  process.exit(2);
});

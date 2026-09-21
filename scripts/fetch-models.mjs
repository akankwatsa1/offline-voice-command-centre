#!/usr/bin/env node
/**
 * Fetch the Needle 3 engine + weights, and stage the Android engine into jniLibs.
 *
 *   node scripts/fetch-models.mjs --platform android-arm64
 *   node scripts/fetch-models.mjs --platform linux-x86_64     # used by CI to run the test suite
 *   node scripts/fetch-models.mjs --all
 *
 * Everything lands under models/ (gitignored). Nothing here is committed, because the
 * weights are 33.7 MB and the engines are per-platform binaries.
 */
import { createWriteStream, mkdirSync, statSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const REPO = "Cactus-Compute/needle3";
const REV = "main";
const BASE = `https://huggingface.co/${REPO}/resolve/${REV}`;

// needle3.cact is the 20-layer model. `needle build --layers N` can slice a smaller
// subnetwork from the same weights; see README.md > Ladder depth.
const WEIGHTS = { path: "needle3.cact", min: 30 * 1024 * 1024 };

// Files each platform folder ships: the engine, and needle.h for native linking.
const PLATFORM_FILES = {
  "android-arm64": ["needle", "needle.h"],
  "android-armv7": ["needle", "needle.h"],
  "linux-x86_64": ["needle", "needle.h"],
  "linux-arm64": ["needle", "needle.h"],
  "macos-arm64": ["needle", "needle.h"],
  "windows-x86_64": ["needle.exe", "needle.h"],
};

const args = process.argv.slice(2);
const getFlag = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const platforms = [];
if (args.includes("--all")) platforms.push(...Object.keys(PLATFORM_FILES));
else {
  const p = getFlag("--platform") ?? "android-arm64";
  platforms.push(...p.split(",").map((s) => s.trim()).filter(Boolean));
}
for (const p of platforms) {
  if (!PLATFORM_FILES[p]) {
    console.error(`unknown platform "${p}". Known: ${Object.keys(PLATFORM_FILES).join(", ")}`);
    process.exit(1);
  }
}

const DEST = join(ROOT, "models");
mkdirSync(DEST, { recursive: true });

/** Resumable, retrying download. Large files over a flaky link need both. */
async function download(url, dest, { minBytes = 1, chunked = false } = {}) {
  if (existsSync(dest) && statSync(dest).size >= minBytes) {
    console.log(`  cached  ${(statSync(dest).size / 1048576).toFixed(2)} MB  ${dest}`);
    return dest;
  }
  const part = dest + ".part";
  // Per-platform files land in models/<platform>/, which may not exist yet.
  mkdirSync(dirname(dest), { recursive: true });
  let total = 0;
  try {
    const head = await fetch(url, { headers: { "user-agent": "voice-command-center" }, redirect: "follow" });
    if (!head.ok) throw new Error(`HTTP ${head.status}`);
    const len = head.headers.get("content-length");
    total = len ? parseInt(len, 10) : 0;
    if (chunked && head.body) await head.body.cancel();
    if (!chunked) {
      await pipeline(Readable.fromWeb(head.body), createWriteStream(part));
    }
  } catch (e) {
    if (!chunked) throw e;
  }

  if (chunked) {
    // Range requests in 2 MB pieces: survives connections that get cut mid-stream.
    let done = existsSync(part) ? statSync(part).size : 0;
    const fd = createWriteStream(part, { flags: done > 0 ? "a" : "w" });
    const CHUNK = 2 * 1024 * 1024;
    while (total === 0 || done < total) {
      const end = total ? Math.min(done + CHUNK - 1, total - 1) : done + CHUNK - 1;
      let ok = false;
      for (let attempt = 1; attempt <= 5 && !ok; attempt++) {
        try {
          const r = await fetch(url, {
            headers: { "user-agent": "voice-command-center", range: `bytes=${done}-${end}` },
            redirect: "follow",
          });
          if (r.status !== 206 && r.status !== 200) throw new Error(`HTTP ${r.status}`);
          const buf = Buffer.from(await r.arrayBuffer());
          if (!buf.length) throw new Error("empty chunk");
          fd.write(buf);
          done += buf.length;
          ok = true;
          if (total) process.stdout.write(`\r  ${(done / 1048576).toFixed(1)} / ${(total / 1048576).toFixed(1)} MB`);
        } catch (e) {
          await new Promise((r) => setTimeout(r, 1000 * attempt));
        }
      }
      if (!ok) throw new Error(`stalled at ${(done / 1048576).toFixed(1)} MB`);
      if (!total && done > 0) break;
    }
    await new Promise((r) => fd.end(r));
    process.stdout.write("\n");
  }

  const size = statSync(part).size;
  if (size < minBytes) throw new Error(`${dest}: got ${size} bytes, expected at least ${minBytes}`);
  const { renameSync } = await import("node:fs");
  renameSync(part, dest);
  console.log(`  saved   ${(size / 1048576).toFixed(2)} MB  ${dest}`);
  return dest;
}

async function main() {
  console.log(`Needle 3 from huggingface.co/${REPO}`);
  console.log(`\nweights`);
  await download(`${BASE}/${WEIGHTS.path}`, join(DEST, WEIGHTS.path), { minBytes: WEIGHTS.min, chunked: true });

  for (const p of platforms) {
    console.log(`\nplatform ${p}`);
    for (const file of PLATFORM_FILES[p]) {
      const min = file.endsWith(".h") ? 200 : 200 * 1024;
      await download(`${BASE}/${p}/${file}`, join(DEST, p, file), { minBytes: min });
    }
  }

  // Android will not execute a file it cannot find on disk, so the engine goes into
  // jniLibs under a .so name: AGP extracts it to the app's native lib dir, which is
  // the one app-owned location that is mounted executable. See android/README.md.
  if (platforms.includes("android-arm64")) {
    const libs = join(ROOT, "android", "app", "src", "main", "jniLibs", "arm64-v8a");
    mkdirSync(libs, { recursive: true });
    const engine = readFileSync(join(DEST, "android-arm64", "needle"));
    writeFileSync(join(libs, "libneedle_engine.so"), engine);
    console.log(`\nstaged  ${(engine.length / 1048576).toFixed(2)} MB  android/app/src/main/jniLibs/arm64-v8a/libneedle_engine.so`);
    const weights = readFileSync(join(DEST, WEIGHTS.path));
    const assets = join(ROOT, "android", "app", "src", "main", "assets");
    mkdirSync(assets, { recursive: true });
    writeFileSync(join(assets, "needle3.cact"), weights);
    console.log(`staged  ${(weights.length / 1048576).toFixed(2)} MB  android/app/src/main/assets/needle3.cact`);
  }

  console.log("\ndone");
}

main().catch((e) => {
  console.error(`\nfetch-models failed: ${e.message}`);
  process.exit(1);
});

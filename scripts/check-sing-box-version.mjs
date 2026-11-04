#!/usr/bin/env node
// Read-only sing-box version watchdog.
//
// The approved pin lives in release/sing-box-pin.json. This script never
// edits the pin, builds, merges, or publishes. It only reports whether a
// newer upstream release exists and optionally verifies the local Windows
// runtime hashes.

import { createHash } from "node:crypto";
import { readFileSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..");
const pinPath = join(repoRoot, "release", "sing-box-pin.json");

const args = new Set(process.argv.slice(2));
const jsonOutput = args.has("--json");
const failOnNew = args.has("--fail-on-new");
const allowUnavailable = args.has("--allow-unavailable");
const runtimeDirArg = valueAfter("--runtime-dir");

function valueAfter(flag) {
  const index = process.argv.indexOf(flag);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

function readPin() {
  const raw = readFileSync(pinPath, "utf8");
  const pin = JSON.parse(raw);
  if (!pin || typeof pin !== "object") throw new Error("pin is not an object");
  if (!pin.version || !pin.tag || !pin.repository) {
    throw new Error("pin must contain version, tag and repository");
  }
  return pin;
}

function parseVersion(value) {
  const cleaned = String(value).trim().replace(/^v/i, "").split("+")[0];
  const [core, prerelease = ""] = cleaned.split("-", 2);
  const parts = core.split(".").map((part) => Number.parseInt(part, 10));
  if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part))) {
    throw new Error(`invalid semantic version: ${value}`);
  }
  return { core: parts, prerelease };
}

function compareVersions(left, right) {
  const a = parseVersion(left);
  const b = parseVersion(right);
  for (let i = 0; i < 3; i += 1) {
    if (a.core[i] !== b.core[i]) return a.core[i] < b.core[i] ? -1 : 1;
  }
  if (a.prerelease === b.prerelease) return 0;
  if (!a.prerelease) return 1;
  if (!b.prerelease) return -1;
  return a.prerelease < b.prerelease ? -1 : 1;
}

function sha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex");
}

function verifyRuntime(pin, dir) {
  const expected = pin.windows?.files ?? {};
  const results = [];
  for (const [name, expectedHash] of Object.entries(expected)) {
    const path = join(dir, name);
    try {
      statSync(path);
    } catch {
      results.push({ name, ok: false, error: "missing" });
      continue;
    }
    const actual = sha256(path);
    results.push({
      name,
      ok: actual === expectedHash,
      expected: expectedHash,
      actual,
    });
  }
  return results;
}

async function fetchLatest(pin) {
  const headers = {
    Accept: "application/vnd.github+json",
    "User-Agent": "GATES-sing-box-version-watch",
  };
  if (process.env.GITHUB_TOKEN) {
    headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  }
  const response = await fetch(
    `https://api.github.com/repos/${pin.repository}/releases/latest`,
    { headers },
  );
  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new Error(`GitHub API ${response.status}: ${body.slice(0, 300)}`);
  }
  return response.json();
}

function printHuman(result) {
  if (result.state === "unavailable") {
    console.log(`sing-box version check unavailable: ${result.error}`);
    console.log(`last known approved pin: ${result.pin.tag} (${result.pin.commit})`);
    console.log("The approved build pin was not changed.");
    return;
  }
  const { pin, latest, newer } = result;
  console.log(`approved pin: ${pin.tag} (${pin.commit})`);
  console.log(`latest upstream: ${latest.tag_name} (${latest.published_at ?? "unknown"})`);
  console.log(`status: ${newer ? "NEWER_RELEASE_AVAILABLE" : "up-to-date"}`);
  if (newer) {
    console.log("");
    console.log("A newer upstream release exists. This is a review trigger, not an upgrade.");
    console.log("Required before changing the pin:");
    console.log("  1. Review upstream release notes and deprecated/removed config fields.");
    console.log("  2. Build and test Windows and Android from the exact candidate commit.");
    console.log("  3. Re-run the native HY2/VLESS/Naive route acceptance.");
    console.log("  4. Update release/sing-box-pin.json with hashes and obtain approval.");
    console.log("  5. Never auto-merge or auto-publish from this check.");
  }
  if (result.runtime?.length) {
    console.log("");
    for (const item of result.runtime) {
      console.log(
        `runtime ${item.name}: ${item.ok ? "ok" : "MISMATCH"}${item.error ? ` (${item.error})` : ""}`,
      );
      if (!item.ok && item.actual) console.log(`  expected ${item.expected}\n  actual   ${item.actual}`);
    }
  }
}

async function main() {
  let pin;
  try {
    pin = readPin();
  } catch (error) {
    console.error(`invalid sing-box pin: ${error.message}`);
    process.exitCode = 2;
    return;
  }

  const runtimeDir = runtimeDirArg ? resolve(runtimeDirArg) : undefined;
  const runtime = runtimeDir ? verifyRuntime(pin, runtimeDir) : undefined;
  const runtimeFailed = runtime?.some((item) => !item.ok) ?? false;

  let latest;
  try {
    latest = await fetchLatest(pin);
  } catch (error) {
    const result = { state: "unavailable", error: error.message, pin, runtime };
    if (jsonOutput) console.log(JSON.stringify(result, null, 2));
    else printHuman(result);
    process.exitCode = runtimeFailed ? 4 : allowUnavailable ? 0 : 3;
    return;
  }

  const newer = compareVersions(latest.tag_name ?? "0.0.0", pin.version) > 0;
  const result = { state: "checked", pin, latest, newer, runtime };
  if (jsonOutput) console.log(JSON.stringify(result, null, 2));
  else printHuman(result);

  process.exitCode = runtimeFailed ? 4 : newer && failOnNew ? 1 : 0;
}

await main();

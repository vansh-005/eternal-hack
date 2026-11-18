#!/usr/bin/env node
// Q-07 (export-tests part): collect the one-line JSON summaries that the R-tests print (TESTING.md section 6)
// from backend/nocook-service/target/failsafe-reports and write results/tests-<yyyymmdd-HHMM>.json
// as an array of {test, result, ...measured values}.
import { readdirSync, readFileSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const reportsDir = process.argv[2] ?? join(root, 'backend', 'nocook-service', 'target', 'failsafe-reports');
const outDir = join(root, 'results');

if (!existsSync(reportsDir)) {
  console.error(`no failsafe reports at ${reportsDir}; run: cd backend && ./mvnw -pl nocook-service -am verify`);
  process.exit(1);
}

const decodeXml = (text) =>
  text.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

const summaries = [];
const seen = new Set();
// The XML reports carry the captured stdout of every class; the *-output.txt/.txt files may repeat it, so dedupe.
for (const file of readdirSync(reportsDir).filter((name) => name.endsWith('.xml')).sort()) {
  const text = decodeXml(readFileSync(join(reportsDir, file), 'utf8'));
  for (const rawLine of text.split(/\r?\n/)) {
    const start = rawLine.indexOf('{"test":');
    if (start < 0) continue;
    const line = rawLine.slice(start).replace(/\]\]>.*$/, '').trim();
    let parsed;
    try {
      parsed = JSON.parse(line);
    } catch {
      continue;
    }
    if (typeof parsed.test !== 'string' || typeof parsed.result !== 'string') continue;
    const key = `${file}|${line}`;
    if (seen.has(key)) continue;
    seen.add(key);
    summaries.push({ ...parsed, class: file.replace(/^TEST-/, '').replace(/\.xml$/, '') });
  }
}

summaries.sort((a, b) => a.test.localeCompare(b.test, 'en', { numeric: true }));

const pad = (n) => String(n).padStart(2, '0');
const now = new Date();
const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}`;
mkdirSync(outDir, { recursive: true });
const outFile = join(outDir, `tests-${stamp}.json`);
writeFileSync(outFile, JSON.stringify(summaries, null, 2) + '\n');

const failed = summaries.filter((entry) => entry.result !== 'PASS');
console.log(`wrote ${outFile}: ${summaries.length} summaries, ${failed.length} not PASS`);
process.exit(failed.length === 0 ? 0 : 2);

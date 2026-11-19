#!/usr/bin/env node
// Q-07: build the README results table from results/run-*/summary.json (OUTPUT.md D9).
//   node tools/readme-table.mjs            print table + headline, update README.md between markers if present
//   node tools/readme-table.mjs --print    never touch README.md
// Markers: <!-- results-table:start --> ... <!-- results-table:end -->   (also accepts RESULTS_TABLE_START / _END)
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const resultsDir = join(root, 'results');
const printOnly = process.argv.includes('--print');

const runs = [];
for (const name of readdirSync(resultsDir).filter((n) => n.startsWith('run-')).sort()) {
  const file = join(resultsDir, name, 'summary.json');
  if (!existsSync(file)) continue;
  if (/-validation/.test(name)) continue; // validation artifacts never go in the README
  runs.push({ name, summary: JSON.parse(readFileSync(file, 'utf8')) });
}

const fmtS = (v) => (v === null || v === undefined ? 'n/a' : v < 1 ? `${Math.round(v * 1000)} ms` : `${v.toFixed(2)} s`);
const pct = (v) => (v === null || v === undefined ? 'n/a' : `${(v * 100).toFixed(2)}%`);
const rows = runs.map(({ name, summary: s }) => {
  const c = s.latency?.caseOpen ?? {};
  const load = s.fault ? `${s.profile} + ${s.fault.id}` : s.profile;
  const reps = s.replicas === 1 ? ' (1 replica)' : '';
  const rate = s.targetRate !== null && s.targetRate !== undefined ? ` ${s.targetRate}/s` : '';
  return `| ${name} | ${s.kind ?? 'MEASURED'} | ${load}${rate}${reps} | ${fmtS(c.p50)} / ${fmtS(c.p95)} / ${fmtS(c.p99)} | ${c.n ?? 'n/a'} | ${pct(c.shareWithinSlo)} | ${fmtS(s.latency?.ackApi?.p99)} | ${s.invariantsPass ? 'pass' : 'FAIL'} | ${s.verdict} |`;
});
const table = [
  '| Run | Label | Load | case-open p50 / p95 / p99 | N | ≤ 2 s | ACK API p99 | Invariants | Verdict |',
  '|---|---|---|---|---|---|---|---|---|',
  ...rows,
].join('\n');

// headline (OUTPUT.md section 6): newest non-fault P3 and P6 summaries, real numbers only
const newest = (pred) => [...runs].reverse().find(({ summary: s }) => pred(s) && s.verdict !== 'VOID')?.summary;
const p3 = newest((s) => s.profile === 'P3' && !s.fault && s.replicas !== 1);
const p6 = newest((s) => s.profile === 'P6' && !s.fault && s.replicas !== 1);
const faultRuns = runs.filter(({ summary: s }) => s.fault && s.verdict !== 'VOID');
let headline = null;
if (p3) {
  const c = p3.latency.caseOpen;
  headline = `At 10× Zomato's record New Year's Eve peak, on one laptop, p99 case-open was ${c.p99 === null ? 'not reported' : `${c.p99} s`} (N = ${c.n}), with ${pct(c.shareWithinSlo)} of cases inside the 2 s SLO.`;
  if (p6?.knee?.casesPerSecond) headline += ` The system's limit was ${p6.knee.casesPerSecond} cases/s, set by ${p6.bottleneck ?? '(bottleneck not recorded)'}.`;
  if (faultRuns.length) headline += ` Across ${faultRuns.length} fault runs: ${faultRuns.every((r) => r.summary.invariantsPass) ? '0 lost cases, 0 duplicate calls' : 'INVARIANT FAILURES - see results'}.`;
}

console.log(table);
console.log(headline ? `\nHeadline: ${headline}` : '\n(no P3 summary yet: headline not available)');

const readme = join(root, 'README.md');
if (!printOnly && existsSync(readme)) {
  const text = readFileSync(readme, 'utf8');
  const pair = [
    ['<!-- results-table:start -->', '<!-- results-table:end -->'],
    ['<!-- RESULTS_TABLE_START -->', '<!-- RESULTS_TABLE_END -->'],
  ].find(([a, b]) => text.includes(a) && text.includes(b));
  if (pair) {
    const [a, b] = pair;
    const next = text.slice(0, text.indexOf(a) + a.length) + '\n' + table + '\n' + text.slice(text.indexOf(b));
    if (next !== text) writeFileSync(readme, next);
    console.log(`\nREADME.md results table updated (${rows.length} runs).`);
  } else {
    console.log('\nREADME.md has no results-table markers: printed only.');
  }
}

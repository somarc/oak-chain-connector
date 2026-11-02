#!/usr/bin/env node
// Validator segment GC + binary GC under a live AEM 6.5 LTS mount.
//
// Proves, against a running opted-in AEM and a GC-enabled validator cluster (mock mode), that:
//   1. AEM reads chain content through the mount (messages and validator-hosted binaries).
//   2. Deletes on the chain disappear from AEM within the refresh interval; control content stays.
//   3. The mounted validator really reclaims: >= 2 completed full GC runs after the deletes, bytes
//      reclaimed, store smaller, deleted binaries released.
//   4. Segments AEM itself fetched (relay log) are physically gone from the validator's TAR files
//      and answer 404, while every current read through AEM keeps answering 200 with exact bytes.
//   5. Writes after GC appear in AEM, and AEM logs no segment/commit/index errors meanwhile.
// It never starts, stops or signals anything; it writes and deletes only its own namespace.
//
// Environment (defaults for the mount/lab setup):
//   OAK_CHAIN_INFRA  oak-chain-infra checkout (tx.mjs, GC evaluators)   ../../../oak-chain-infra
//   VALIDATORS       comma list of cluster members                       http://127.0.0.1:8390,8392,8394
//   MOUNTED          the member AEM mounts (direct, not the relay)       first of VALIDATORS
//   STORE            the mounted member's segment store directory        (required)
//   RELAY_LOG        relay.py request log of AEM's mount traffic         (required)
//   AEM, AEM_AUTH    AEM author URL and user:password                    http://localhost:4502, admin:admin
//   AEM_HOME         opted-in AEM home (for crx-quickstart/logs/error.log) (required)
//   EVIDENCE         evidence directory                                  (required)
//   GC_DEADLINE_MS   wait for GC after the last delete                   900000
//   READER_MS        pause between reader rounds through AEM             1000
// Exit: 0 PASS, 1 FAIL, 2 refused before any write.

import { createHash, randomBytes } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, openSync, readSync, closeSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const env = process.env;
const need = (k) => { if (!env[k]) { console.error(`${k} is required`); process.exit(2); } return env[k]; };
const INFRA = resolve(env.OAK_CHAIN_INFRA ?? new URL('../../../oak-chain-infra', import.meta.url).pathname);
const VALIDATORS = (env.VALIDATORS ?? 'http://127.0.0.1:8390,http://127.0.0.1:8392,http://127.0.0.1:8394').split(',');
const MOUNTED = env.MOUNTED ?? VALIDATORS[0];
const STORE = need('STORE');
const RELAY_LOG = need('RELAY_LOG');
const AEM = env.AEM ?? 'http://localhost:4502';
const AEM_AUTH = 'Basic ' + Buffer.from(env.AEM_AUTH ?? 'admin:admin').toString('base64');
const AEM_HOME = need('AEM_HOME');
const EV = need('EVIDENCE');
const GC_DEADLINE_MS = Number(env.GC_DEADLINE_MS ?? 900_000);
const READER_MS = Number(env.READER_MS ?? 1000);
const PLAN = { controlMessages: 2, controlBinaries: 1, bulkWrites: 16, bulkChars: 180_000, doomedBinaries: 3, binaryBytes: 20_480 };

const { buildWrite, buildDelete, testKey } = await import(pathToFileURL(join(INFRA, 'src/tx.mjs')));
const { classifyOperation } = await import(pathToFileURL(join(INFRA, 'src/harness/client.mjs')));
const { evaluateSegmentGc, evaluateBinaryDeletes, recordRuns, filler } = await import(pathToFileURL(join(INFRA, 'src/federation-gc.mjs')));

mkdirSync(EV, { recursive: true });
const sha256 = (b) => createHash('sha256').update(b).digest('hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const now = () => Date.now();
const t0 = now();
const log = (m) => { const line = `[${((now() - t0) / 1000).toFixed(1).padStart(6)}s] ${m}`; console.log(line); appendFileSync(join(EV, 'run.log'), line + '\n'); };
const assertions = [];
const assert = (name, ok, expected, observed) => {
  assertions.push({ name, ok, expected, observed });
  appendFileSync(join(EV, 'assertions.tsv'), `${ok ? 'PASS' : 'FAIL'}\t${name}\t${expected}\t${typeof observed === 'string' ? observed : JSON.stringify(observed)}\n`);
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}: ${typeof observed === 'string' ? observed : JSON.stringify(observed)}`);
  return ok;
};
class Abort extends Error {}

let seq = 0;
async function call(label, method, url, { form, files, headers = {}, raw = false, timeoutMs = 30_000 } = {}) {
  const id = ++seq;
  const started = now();
  let body;
  if (files) {
    body = new FormData();
    for (const [k, v] of Object.entries(form ?? {})) body.append(k, String(v));
    for (const f of files) body.append(f.field, new Blob([f.bytes], { type: f.type }), f.name);
  } else if (form) {
    body = new URLSearchParams(Object.entries(form).map(([k, v]) => [k, String(v)]));
  }
  let status = 0; let json = null; let bytes = null; let location = null; let error = null;
  try {
    const res = await fetch(url, { method, body, headers, redirect: 'manual', signal: AbortSignal.timeout(timeoutMs) });
    status = res.status;
    location = res.headers.get('location');
    const buf = Buffer.from(await res.arrayBuffer());
    if (raw) bytes = buf;
    else { try { json = JSON.parse(buf.toString('utf8')); } catch { json = null; } }
  } catch (e) { error = String(e?.cause?.code ?? e?.message ?? e); }
  const ms = now() - started;
  appendFileSync(join(EV, 'requests.ndjson'), JSON.stringify({ id, at: new Date(started).toISOString(), label, method, url, status, ms, error }) + '\n');
  return { id, status, json, bytes, location, ms, error };
}
const aem = (label, path, opts = {}) => call(label, 'GET', `${AEM}${path}`, { ...opts, headers: { Authorization: AEM_AUTH } });

async function waitFor(label, fn, deadlineMs, pollMs = 2000) {
  const end = now() + deadlineMs;
  let last;
  while (now() < end) {
    last = await fn();
    if (last.done) return { ok: true, ...last };
    await sleep(pollMs);
  }
  return { ok: false, ...last };
}

async function leader() {
  for (const v of VALIDATORS) {
    const r = await call('leader probe', 'GET', `${v}/v1/consensus/status`);
    if (r.json?.currentRole === 'LEADER') return v;
  }
  throw new Abort('no leader');
}
async function submit(label, route, form, files) {
  let url = `${await leader()}${route}`;
  for (let i = 0; i < 6; i += 1) {
    const r = await call(label, 'POST', url, { form, files });
    if (r.status === 307 && r.location) { url = r.location.replace('//localhost:', '//127.0.0.1:'); continue; }
    if (r.status === 503 && JSON.stringify(r.json ?? '').includes('no_quorum')) { await sleep(2000); continue; }
    return r;
  }
  throw new Abort(`${label}: no answer after redirects`);
}
async function committed(label, proposalId) {
  const w = await waitFor(`${label} committed`, async () => {
    const r = await call(`${label} op`, 'GET', `${await leader()}/v1/ops/operations/${proposalId}`);
    const v = r.status === 200 ? classifyOperation(r.json) : null;
    return { done: Boolean(v), verdict: v, state: r.json?.state ?? r.status };
  }, 90_000, 1000);
  return w.verdict?.outcome ?? `not settled (${w.state})`;
}

// Segment ids in the mounted member's TAR files (entry names "<uuid>.<crc>").
function tarSegments(dir) {
  const ids = new Set();
  let bytes = 0;
  for (const f of readdirSync(dir).filter((n) => /^data\d+[a-z]\.tar$/.test(n)).sort()) {
    const p = join(dir, f);
    const size = statSync(p).size;
    bytes += size;
    const fd = openSync(p, 'r');
    const h = Buffer.alloc(512);
    let off = 0;
    try {
      while (off + 512 <= size) {
        readSync(fd, h, 0, 512, off);
        const name = h.toString('ascii', 0, 100).replace(/\0.*$/s, '');
        if (!name) break;
        const len = parseInt(h.toString('ascii', 124, 136).replace(/\0.*$/s, '').trim(), 8);
        if (!Number.isFinite(len)) break;
        const m = /^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\./.exec(name);
        if (m) ids.add(m[1]);
        off += 512 + Math.ceil(len / 512) * 512;
      }
    } finally { closeSync(fd); }
  }
  return { ids, bytes };
}
const fetchedSegments = () => new Set([...readFileSync(RELAY_LOG, 'utf8').matchAll(/ (?:GET|HEAD) \/segments\/([0-9a-f-]{36})/g)].map((m) => m[1]));

const errorLog = join(AEM_HOME, 'crx-quickstart/logs/error.log');
const errorLogStart = existsSync(errorLog) ? statSync(errorLog).size : 0;
function aemLogScan() {
  const text = readFileSync(errorLog, 'utf8').slice(errorLogStart);
  const lines = text.split('\n');
  const count = (re) => lines.filter((l) => re.test(l)).length;
  const sample = (re) => lines.filter((l) => re.test(l)).slice(0, 5);
  return {
    lines: lines.length,
    error: count(/\*ERROR\*/), errorSample: sample(/\*ERROR\*/),
    segmentNotFound: count(/SegmentNotFound/), commitFailed: count(/CommitFailedException/),
    asyncIndexProblems: count(/AsyncIndexUpdate.*(\*WARN\*|\*ERROR\*)|(\*WARN\*|\*ERROR\*).*AsyncIndexUpdate/),
    mountWarnings: count(/(\*WARN\*|\*ERROR\*).*(oakchain|OakChain|LazyRemoteNodeStore|ValidatorHttpClient)/),
    mountSample: sample(/(\*WARN\*|\*ERROR\*).*(oakchain|OakChain|LazyRemoteNodeStore|ValidatorHttpClient)/),
    compositeRegistrations: count(/CompositeNodeStoreService.*[Rr]egister/),
  };
}

// --- reader: control content through AEM, every READER_MS, for the whole run ---
const reads = [];
let readerOn = false;
async function readerLoop(control) {
  readerOn = true;
  while (readerOn) {
    for (const item of control) {
      const at = now();
      if (item.kind === 'message') {
        const r = await aem(`reader ${item.id}`, `${item.storagePath}.json`);
        reads.push({ at, id: item.id, status: r.status, ms: r.ms, ok: r.status === 200 && r.json?.message === item.message });
      } else {
        const r = await aem(`reader ${item.id}`, `${item.storagePath}/jcr:data`, { raw: true });
        reads.push({ at, id: item.id, status: r.status, ms: r.ms, ok: r.status === 200 && sha256(r.bytes ?? Buffer.alloc(0)) === item.sha256 });
      }
    }
    await sleep(READER_MS);
  }
}

async function main() {
  const nonce = randomBytes(4).toString('hex');
  const key = testKey(`aem-gc/${nonce}`);
  const ns = `/oak-chain/${key.address.slice(2, 4)}/${key.address.slice(4, 6)}/${key.address.slice(6, 8)}/${key.address}`;
  writeFileSync(join(EV, 'plan.json'), JSON.stringify({ nonce, address: key.address, ns, PLAN, VALIDATORS, MOUNTED, AEM, STORE, GC_DEADLINE_MS }, null, 2));
  log(`namespace ${ns}`);

  // P0 preflight
  const root = await aem('p0 mount root', '/oak-chain.json');
  if (!assert('p0-aem-mount-up', root.status === 200, 'AEM serves /oak-chain', `HTTP ${root.status}`)) throw new Abort('AEM mount not up');
  const baseline = {};
  for (const v of VALIDATORS) {
    const s = await call('p0 gc status', 'GET', `${v}/v1/gc/status`);
    baseline[v] = s.json?.binary?.deletes ?? {};
    if (!s.json?.segment) throw new Abort(`${v} serves no segment GC status (wrong jar?)`);
  }

  // P1 write
  const items = [];
  const msg = (what) => `[aem gc ${nonce}] ${what}`;
  for (let i = 0; i < PLAN.controlMessages; i += 1) items.push({ id: `control-${i}`, kind: 'message', role: 'control', message: msg(`control ${i}`) });
  for (let i = 0; i < PLAN.bulkWrites; i += 1) { const h = msg(`bulk ${i} `); items.push({ id: `bulk-${i}`, kind: 'message', role: 'doomed', message: h + filler(`${nonce}/${i}`, PLAN.bulkChars - h.length) }); }
  for (let i = 0; i < PLAN.controlBinaries + PLAN.doomedBinaries; i += 1) {
    const bytes = randomBytes(PLAN.binaryBytes);
    items.push({ id: `binary-${i}`, kind: 'binary', role: i < PLAN.controlBinaries ? 'control' : 'doomed', message: msg(`binary ${i}`), bytes, sha256: sha256(bytes) });
  }
  log(`P1 write ${items.length} items`);
  for (const item of items) {
    const form = buildWrite({ contentType: item.kind === 'binary' ? 'file' : 'page', key: key.privateKey, message: item.message });
    item.proposalId = form.proposalId;
    const files = item.kind === 'binary' ? [{ field: 'file', name: `${item.id}.bin`, type: 'application/octet-stream', bytes: item.bytes }] : null;
    const r = await submit(`p1 write ${item.id}`, '/v1/propose-write', form, files);
    item.storagePath = r.json?.storagePath;
    if (r.status !== 202 || !item.storagePath?.startsWith(`${ns}/`)) throw new Abort(`write ${item.id}: HTTP ${r.status} ${JSON.stringify(r.json)?.slice(0, 200)}`);
  }
  const outcomes = [];
  for (const item of items) outcomes.push(await committed(`p1 ${item.id}`, item.proposalId));
  if (!assert('p1-writes-committed', outcomes.every((o) => o === 'COMMITTED'), 'every write COMMITTED', outcomes.filter((o) => o !== 'COMMITTED').length ? outcomes : `${outcomes.length} committed`)) throw new Abort('writes');

  // P2 AEM sees everything (messages by content, binaries by bytes)
  const seen = await waitFor('p2 AEM sees all', async () => {
    const missing = [];
    for (const item of items) {
      if (item.kind === 'message') {
        const r = await aem(`p2 ${item.id}`, `${item.storagePath}.json`);
        if (!(r.status === 200 && r.json?.message === item.message)) missing.push(`${item.id}:${r.status}`);
      } else {
        const r = await aem(`p2 ${item.id}`, `${item.storagePath}/jcr:data`, { raw: true });
        if (!(r.status === 200 && sha256(r.bytes ?? Buffer.alloc(0)) === item.sha256)) missing.push(`${item.id}:${r.status}`);
      }
    }
    return { done: missing.length === 0, missing };
  }, 120_000);
  if (!assert('p2-aem-reads-all', seen.ok, 'all messages and binary bytes readable through AEM', seen.ok ? `${items.length} items` : seen.missing)) throw new Abort('AEM did not see the writes');

  const control = items.filter((i) => i.role === 'control');
  const doomed = items.filter((i) => i.role === 'doomed');
  const reader = readerLoop(control);

  // P3 inventory before GC
  const before = tarSegments(STORE);
  const fetchedBefore = fetchedSegments();
  log(`P3 validator store: ${before.ids.size} segments, ${before.bytes} bytes; AEM fetched ${fetchedBefore.size} distinct segments so far`);

  // P4 delete the doomed items
  log(`P4 delete ${doomed.length} items`);
  for (const item of doomed) {
    const form = buildDelete({ contentPath: item.storagePath, key: key.privateKey });
    item.deleteId = form.proposalId;
    const r = await submit(`p4 delete ${item.id}`, '/v1/propose-delete', form);
    if (r.status !== 202) throw new Abort(`delete ${item.id}: HTTP ${r.status}`);
  }
  const dOutcomes = [];
  for (const item of doomed) dOutcomes.push(await committed(`p4 ${item.id}`, item.deleteId));
  const gcSince = now();
  assert('p4-deletes-committed', dOutcomes.every((o) => o === 'COMMITTED'), 'every delete COMMITTED', dOutcomes.filter((o) => o !== 'COMMITTED').length ? dOutcomes : `${dOutcomes.length} committed`);
  const gone = await waitFor('p4 AEM drops deleted', async () => {
    const still = [];
    for (const item of doomed) { const r = await aem(`p4 gone ${item.id}`, `${item.storagePath}.json`); if (r.status !== 404) still.push(`${item.id}:${r.status}`); }
    return { done: still.length === 0, still };
  }, 60_000, 1000);
  assert('p4-aem-drops-deleted', gone.ok, 'deleted paths 404 in AEM within 60 s', gone.ok ? `${doomed.length} gone` : gone.still);

  // P5 wait for real reclamation on the mounted member (and record the others)
  log(`P5 waiting up to ${GC_DEADLINE_MS / 1000}s for >= 2 full GC runs and binary release`);
  const runs = Object.fromEntries(VALIDATORS.map((v) => [v, new Map()]));
  const gc = await waitFor('p5 gc', async () => {
    const state = {};
    for (const v of VALIDATORS) {
      const s = await call('p5 gc status', 'GET', `${v}/v1/gc/status`);
      if (s.json) { recordRuns(runs[v], s.json); appendFileSync(join(EV, 'gc-status.ndjson'), JSON.stringify({ at: now(), v, body: s.json }) + '\n'); }
      state[v] = { seg: evaluateSegmentGc(runs[v].values(), gcSince), bin: evaluateBinaryDeletes(baseline[v], s.json?.binary?.deletes, PLAN.doomedBinaries) };
    }
    return { done: state[MOUNTED].seg.ok && state[MOUNTED].bin.ok, state };
  }, GC_DEADLINE_MS, 5000);
  writeFileSync(join(EV, 'gc.json'), JSON.stringify(gc.state, null, 2));
  const m = gc.state?.[MOUNTED];
  assert('p5-segment-gc-reclaimed', Boolean(m?.seg.ok), '>= 2 completed full runs after the deletes, bytes reclaimed, store smaller', m?.seg);
  assert('p5-binary-gc-released', Boolean(m?.bin.ok), `${PLAN.doomedBinaries} deleted binaries released`, m?.bin);

  // P6 physical proof: segments AEM fetched are gone from the TAR files and answer 404
  const after = tarSegments(STORE);
  const fetched = fetchedSegments();
  const reclaimed = [...before.ids].filter((id) => !after.ids.has(id));
  const fetchedReclaimed = reclaimed.filter((id) => fetched.has(id));
  log(`P6 store ${before.ids.size} -> ${after.ids.size} segments, ${before.bytes} -> ${after.bytes} bytes; reclaimed ${reclaimed.length}; of them fetched by AEM ${fetchedReclaimed.length}`);
  assert('p6-segments-reclaimed', reclaimed.length > 0, 'segments present before GC are gone from the TAR files', `${reclaimed.length} reclaimed`);
  assert('p6-aem-fetched-segments-reclaimed', fetchedReclaimed.length > 0, 'some segments AEM read through the mount were physically reclaimed', `${fetchedReclaimed.length} of ${fetched.size} fetched`);
  const probes = [];
  for (const id of fetchedReclaimed.slice(0, 50)) probes.push((await call('p6 probe', 'GET', `${MOUNTED}/segments/${id}`)).status);
  assert('p6-reclaimed-segments-404', probes.length > 0 && probes.every((s) => s === 404), 'GET /segments/{id} 404 for reclaimed segments AEM had fetched', `${probes.filter((s) => s === 404).length}/${probes.length} 404`);

  // P7 writes after GC reach AEM
  const late = { id: 'after-gc', kind: 'message', message: msg('written after GC') };
  const lf = buildWrite({ contentType: 'page', key: key.privateKey, message: late.message });
  const lr = await submit('p7 write after gc', '/v1/propose-write', lf);
  late.storagePath = lr.json?.storagePath;
  const lo = await committed('p7 after-gc', lf.proposalId);
  const lateSeen = await waitFor('p7 AEM sees after-gc write', async () => {
    const r = await aem('p7 after-gc', `${late.storagePath}.json`);
    return { done: r.status === 200 && r.json?.message === late.message, status: r.status };
  }, 60_000, 1000);
  assert('p7-write-after-gc-visible', lo === 'COMMITTED' && lateSeen.ok, 'a write after GC is readable through AEM within 60 s', `${lo}, AEM ${lateSeen.status}`);

  // P8 reader verdict and AEM log
  readerOn = false;
  await reader;
  const bad = reads.filter((r) => !r.ok);
  const maxMs = Math.max(...reads.map((r) => r.ms));
  writeFileSync(join(EV, 'reader.json'), JSON.stringify({ reads: reads.length, bad, maxMs }, null, 2));
  assert('p8-reader-no-failures', reads.length > 0 && bad.length === 0, 'every control read through AEM during deletes and GC: 200 with exact content/bytes', `${reads.length} reads, ${bad.length} bad, max ${maxMs} ms`);
  const scan = aemLogScan();
  writeFileSync(join(EV, 'aem-log-scan.json'), JSON.stringify(scan, null, 2));
  assert('p8-aem-no-segment-not-found', scan.segmentNotFound === 0, 'no SegmentNotFoundException in AEM', `${scan.segmentNotFound}`);
  assert('p8-aem-no-commit-failures', scan.commitFailed === 0, 'no CommitFailedException in AEM', `${scan.commitFailed}`);
  assert('p8-aem-async-index-clean', scan.asyncIndexProblems === 0, 'no AsyncIndexUpdate WARN/ERROR', `${scan.asyncIndexProblems}`);
  assert('p8-aem-no-error-lines', scan.error === 0, 'no ERROR lines in AEM during the run', scan.error === 0 ? '0' : scan.errorSample);
}

try {
  await main();
} catch (e) {
  readerOn = false;
  assert('run-completed', false, 'no abort', String(e?.message ?? e));
}
const failed = assertions.filter((a) => !a.ok);
writeFileSync(join(EV, 'summary.json'), JSON.stringify({ result: failed.length ? 'FAIL' : 'PASS', assertions, seconds: (now() - t0) / 1000 }, null, 2));
log(`RESULT: ${failed.length ? `FAIL (${failed.length})` : 'PASS'} in ${((now() - t0) / 1000).toFixed(0)}s`);
process.exit(failed.length ? 1 : 0);

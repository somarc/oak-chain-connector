#!/usr/bin/env node
// Every node under /oak-chain carries a NAME primary type, on every validator.
//
// Writes two signed records from a fresh test wallet (one under an organization, one not),
// waits for both to commit, then walks /oak-chain on each validator through the explorer API
// and asserts:
//   1. every node has jcr:primaryType, typed NAME (none missing, none STRING);
//   2. every node on a written path above its record is oak:Unstructured, the record nt:unstructured;
//   3. the same holds for the genesis wallet path and the genesis record;
//   4. all validators report the same path -> type listing;
//   5. with AEM set: AEM reads every node through the mount with the same primary type, and its
//      error.log gains no ERROR line at all meanwhile.
// It never starts, stops or signals anything; it writes only to its own fresh wallet.
//
// Environment:
//   OAK_CHAIN_INFRA  oak-chain-infra checkout (tx.mjs)          ../../../oak-chain-infra
//   VALIDATORS       comma list of cluster members               http://127.0.0.1:8090,8092,8094
//   EVIDENCE         directory for listings and assertions       (optional)
//   AEM, AEM_AUTH    opted-in AEM author and user:password       (optional), admin:admin
//   AEM_HOME         that AEM's home, for crx-quickstart/logs/error.log (required with AEM)
// Exit: 0 PASS, 1 FAIL.

import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const env = process.env;
const INFRA = resolve(env.OAK_CHAIN_INFRA ?? new URL('../../../oak-chain-infra', import.meta.url).pathname);
const VALIDATORS = (env.VALIDATORS ?? 'http://127.0.0.1:8090,http://127.0.0.1:8092,http://127.0.0.1:8094').split(',');
const EV = env.EVIDENCE;
const { buildWrite, testKey } = await import(pathToFileURL(join(INFRA, 'src/tx.mjs')));
const { classifyOperation } = await import(pathToFileURL(join(INFRA, 'src/harness/client.mjs')));

const CONTAINER = 'oak:Unstructured';
const RECORD = 'nt:unstructured';
const GENESIS = '0x0000000000000000000000000000000000000000';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const assert = (name, ok, observed) => {
  results.push({ name, ok, observed });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}: ${typeof observed === 'string' ? observed : JSON.stringify(observed)}`);
};

async function getJson(url, init) {
  const res = await fetch(url, { redirect: 'manual', signal: AbortSignal.timeout(30_000), ...init });
  let json = null;
  try { json = await res.json(); } catch { /* not json */ }
  return { status: res.status, json, location: res.headers.get('location') };
}
async function leader() {
  for (const v of VALIDATORS) if ((await getJson(`${v}/v1/consensus/status`)).json?.currentRole === 'LEADER') return v;
  throw new Error('no leader');
}
async function write(fields) {
  let url = `${await leader()}/v1/propose-write`;
  for (let i = 0; i < 6; i += 1) {
    const r = await getJson(url, { method: 'POST', body: new URLSearchParams(fields) });
    if (r.status === 307 && r.location) { url = r.location.replace('//localhost:', '//127.0.0.1:'); continue; }
    if (r.status >= 300) throw new Error(`write ${fields.proposalId}: HTTP ${r.status} ${JSON.stringify(r.json)}`);
    break;
  }
  for (let t = 0; t < 90; t += 1) {
    const op = await getJson(`${await leader()}/v1/ops/operations/${fields.proposalId}`);
    const verdict = op.status === 200 ? classifyOperation(op.json) : null;
    if (verdict) return verdict.outcome;
    await sleep(1000);
  }
  return 'not settled';
}
const shard = (wallet) => {
  const hex = wallet.toLowerCase().replace(/^0x/, '');
  return `/oak-chain/${hex.slice(0, 2)}/${hex.slice(2, 4)}/${hex.slice(4, 6)}/0x${hex}`;
};

async function walk(validator) {
  const cluster = (await getJson(`${validator}/v1/explorer/content/nav`)).json?.localCluster?.clusterId;
  const base = `${validator}/v1/explorer/content/clusters/${cluster}`;
  const nodes = new Map();
  const queue = ['/oak-chain'];
  while (queue.length) {
    const path = queue.shift();
    const node = (await getJson(`${base}/node?path=${encodeURIComponent(path)}`)).json;
    const type = (node?.properties ?? []).find((p) => p.name === 'jcr:primaryType');
    nodes.set(path, type ? `${type.value} (${type.type})` : 'MISSING');
    for (let offset = 0; offset !== null && offset !== undefined;) {
      const page = (await getJson(`${base}/tree?path=${encodeURIComponent(path)}&offset=${offset}&limit=500`)).json;
      for (const child of page?.children ?? []) queue.push(child.path);
      offset = page?.childrenPage?.nextOffset;
    }
  }
  return nodes;
}

const key = testKey(0, `typed-tree-${Date.now()}`);
const wallet = key.address.toLowerCase();
const outcomes = [
  await write(buildWrite({ key: key.privateKey, organization: 'typecheck', message: `typed-tree org ${Date.now()}` })),
  await write(buildWrite({ key: key.privateKey, message: `typed-tree plain ${Date.now()}` })),
];
assert('writes-committed', outcomes.every((o) => o === 'COMMITTED'), outcomes.join(','));
await sleep(3000);

const listings = [];
for (const v of VALIDATORS) listings.push([v, await walk(v)]);
const [, first] = listings[0];

const bad = [...first].filter(([, t]) => !t.endsWith('(NAME)'));
assert('every-node-typed-as-name', bad.length === 0, bad.length ? bad.slice(0, 10) : `${first.size} nodes`);

const expect = (path, type) => first.get(path) === `${type} (NAME)`;
const root = shard(wallet);
const parts = root.split('/').slice(1);
const writePaths = [...parts.map((_, i) => `/${parts.slice(0, i + 1).join('/')}`), `${root}/typecheck`, `${root}/typecheck/content`, `${root}/content`];
const childOf = (p, parent) => p.startsWith(`${parent}/`) && !p.slice(parent.length + 1).includes('/');
const records = [...first.keys()].filter((p) => childOf(p, `${root}/typecheck/content`) || childOf(p, `${root}/content`));
assert('write-path-containers', writePaths.every((p) => expect(p, CONTAINER)),
  writePaths.filter((p) => !expect(p, CONTAINER)).map((p) => `${p} = ${first.get(p)}`).join('; ') || `${writePaths.length} containers`);
assert('write-records', records.length === 2 && records.every((p) => expect(p, RECORD)), records.map((p) => `${p} = ${first.get(p)}`).join('; '));

const g = shard(GENESIS);
const genesisPath = ['/oak-chain', '/oak-chain/00', '/oak-chain/00/00', '/oak-chain/00/00/00', g, `${g}/content`];
assert('genesis-path-containers', genesisPath.every((p) => expect(p, CONTAINER)), genesisPath.map((p) => first.get(p)).join(', '));
assert('genesis-record', expect(`${g}/content/genesis`, RECORD), first.get(`${g}/content/genesis`));

const flat = (m) => JSON.stringify([...m].sort());
const diverged = listings.filter(([, m]) => flat(m) !== flat(first)).map(([v]) => v);
assert('members-agree', diverged.length === 0, diverged.length ? diverged : `${listings.length} members, ${first.size} nodes each`);

if (env.AEM) {
  const auth = { Authorization: `Basic ${Buffer.from(env.AEM_AUTH ?? 'admin:admin').toString('base64')}` };
  const errorLog = join(env.AEM_HOME, 'crx-quickstart/logs/error.log');
  const logStart = statSync(errorLog).size;
  for (let t = 0; t < 60 && (await getJson(`${env.AEM}${records[0]}.json`, { headers: auth })).status !== 200; t += 1) await sleep(1000);
  const differ = [];
  for (const [path, type] of first) {
    const r = await getJson(`${env.AEM}${path}.json`, { headers: auth });
    const seen = r.json?.['jcr:primaryType'];
    if (r.status !== 200 || `${seen} (NAME)` !== type) differ.push(`${path}: HTTP ${r.status} ${seen} vs ${type}`);
  }
  assert('aem-reads-every-node-with-its-type', differ.length === 0, differ.length ? differ.slice(0, 5) : `${first.size} nodes`);
  const errors = readFileSync(errorLog).subarray(logStart).toString('utf8').split('\n').filter((l) => l.includes('*ERROR*'));
  assert('aem-no-errors-while-reading', errors.length === 0, errors.length ? errors.slice(0, 3) : '0 lines');
}

if (EV) {
  mkdirSync(EV, { recursive: true });
  for (const [v, m] of listings) writeFileSync(join(EV, `types-${new URL(v).port}.tsv`), [...m].sort().map(([p, t]) => `${p}\t${t}`).join('\n') + '\n');
  writeFileSync(join(EV, 'assertions.json'), JSON.stringify({ wallet, results }, null, 1));
}
const failed = results.filter((r) => !r.ok).length;
console.log(`RESULT: ${failed ? `FAIL (${failed})` : 'PASS'}`);
process.exit(failed ? 1 : 0);

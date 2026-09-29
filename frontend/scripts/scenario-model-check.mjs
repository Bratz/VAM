/**
 * Runnable check for removeShadow's cascade (utils/simulator/scenarioModel.ts).
 *
 * Deleting a shadow has to take its dependants with it — a rule whose target it
 * was, a pool it kept above two members — or the scenario is left holding
 * dangling localIds. That is branchy enough to be worth pinning down, and it is
 * the kind of thing that breaks silently: the UI still renders, the numbers are
 * just wrong.
 *
 * No test framework is installed, so this is plain node + assert, transpiled
 * through the esbuild that Vite already brings. Run it with:
 *   node scripts/scenario-model-check.mjs
 */
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { pathToFileURL } from 'node:url';
import { writeFileSync, mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

async function load(entry, name) {
  const out = await build({
    entryPoints: [entry],
    bundle: true, format: 'esm', platform: 'node', write: false, logLevel: 'silent',
  });
  const f = join(mkdtempSync(join(tmpdir(), 'sim-check-')), name);
  writeFileSync(f, out.outputFiles[0].text);
  return import(pathToFileURL(f).href);
}

const bundled = await build({
  entryPoints: ['src/utils/simulator/scenarioModel.ts'],
  bundle: true,
  format: 'esm',
  platform: 'node',
  write: false,
  logLevel: 'silent',
});
const dir = mkdtempSync(join(tmpdir(), 'scenario-model-'));
const file = join(dir, 'scenarioModel.mjs');
writeFileSync(file, bundled.outputFiles[0].text);
const { removeShadow } = await import(pathToFileURL(file).href);

const shadow = (localId, extra = {}) => ({
  localId,
  physicalAccountId: `pa-${localId}`,
  proposedVaName: localId,
  role: 'CHILD',
  snapshotBankCode: 'BANK',
  snapshotBankName: 'Bank',
  snapshotBankRelationship: 'HOME',
  snapshotDataSource: 'CORE_BANKING',
  snapshotCurrencyCode: 'AED',
  ...extra,
});
const rule = (localId, sourceLocalIds, targetLocalId) => ({
  localId, ruleName: localId, sweepType: 'ZERO_BALANCE', frequency: 'DAILY',
  sourceLocalIds, targetLocalId, isCrossBank: false, isCrossBorder: false,
});
const pool = (localId, memberLocalIds) => ({
  localId, poolName: localId, poolCurrency: 'AED', poolRatePct: 1, memberLocalIds,
});

let checks = 0;
const check = (name, fn) => { fn(); checks++; console.log('  ok  ' + name); };

check('removes the shadow itself', () => {
  const r = removeShadow([shadow('a'), shadow('b')], [], [], 'a');
  assert.deepEqual(r.shadows.map((s) => s.localId), ['b']);
});

check('drops a rule whose TARGET was removed', () => {
  const r = removeShadow([shadow('a'), shadow('b')], [rule('r1', ['a'], 'b')], [], 'b');
  assert.equal(r.rules.length, 0);
  assert.deepEqual(r.removedRules.map((x) => x.localId), ['r1']);
});

check('drops a rule whose LAST source was removed', () => {
  const r = removeShadow([shadow('a'), shadow('b')], [rule('r1', ['a'], 'b')], [], 'a');
  assert.equal(r.rules.length, 0);
  assert.deepEqual(r.removedRules.map((x) => x.localId), ['r1']);
});

check('KEEPS a rule that still has another source', () => {
  const r = removeShadow(
    [shadow('a'), shadow('b'), shadow('c')],
    [rule('r1', ['a', 'b'], 'c')], [], 'a',
  );
  assert.equal(r.rules.length, 1);
  assert.deepEqual(r.rules[0].sourceLocalIds, ['b'], 'source is pruned, rule survives');
  assert.equal(r.removedRules.length, 0);
});

check('drops a pool that falls below two members', () => {
  const r = removeShadow([shadow('a'), shadow('b')], [], [pool('p1', ['a', 'b'])], 'a');
  assert.equal(r.pools.length, 0);
  assert.deepEqual(r.removedPools.map((x) => x.localId), ['p1']);
});

check('KEEPS a pool that still has two members', () => {
  const r = removeShadow(
    [shadow('a'), shadow('b'), shadow('c')],
    [], [pool('p1', ['a', 'b', 'c'])], 'a',
  );
  assert.equal(r.pools.length, 1);
  assert.deepEqual(r.pools[0].memberLocalIds, ['b', 'c']);
});

check('detaches a child instead of deleting it', () => {
  const r = removeShadow(
    [shadow('parent', { role: 'HEADER' }), shadow('kid', { parentLocalId: 'parent' })],
    [], [], 'parent',
  );
  assert.deepEqual(r.shadows.map((s) => s.localId), ['kid'], 'the child survives');
  assert.equal(r.shadows[0].parentLocalId, undefined, 'its dangling parent ref is cleared');
  assert.equal(r.detachedChildren, 1);
});

check('leaves untouched collections identical', () => {
  const rules = [rule('r1', ['b'], 'c')];
  const r = removeShadow([shadow('a'), shadow('b'), shadow('c')], rules, [], 'a');
  assert.equal(r.rules[0], rules[0], 'an unaffected rule is not needlessly copied');
});

// ---------------------------------------------------------------------------
// poolBasketKind (scoreFormulas/interestYield.ts)
//
// Both a PROPOSED pool and an already-LIVE one put a contribution in the same
// `pool` basket, so a scenario showing "0 pools" can still report its whole
// interest basket as pooled. The disclosure names which, and this pins that
// down — getting it wrong puts a misleading label on a real figure.
// ---------------------------------------------------------------------------
const { poolBasketKind } = await load('src/utils/simulator/scoreFormulas/interestYield.ts', 'interestYield.mjs');

const acct = (id, bal = 1_000_000, rate = 4) => [id, {
  id, accountNumber: id, accountName: id, currencyCode: 'AED',
  currentBalance: bal, interestRate: rate, bankCode: 'B', bankName: 'B', dataSource: 'CORE_BANKING',
}];
const inputs = ({ shadows, rules = [], pools = [], live = [] }) => ({
  proposedShadows: shadows,
  proposedRules: rules,
  proposedPools: pools,
  accountsById: new Map(shadows.map((s) => acct(s.physicalAccountId))),
  chargeConfigs: [],
  pooledByPhys: new Map(live.map((id) => [id, { interestRate: 3.5, poolReference: 'NP-0001', poolCurrency: 'AED' }])),
});
const sh = (localId, role = 'HEADER') => shadow(localId, { role, physicalAccountId: `pa-${localId}` });

check('poolBasketKind: none when nothing is pooled', () => {
  assert.equal(poolBasketKind(inputs({ shadows: [sh('a')] })), 'none');
});

check('poolBasketKind: live when the membership is live, with no proposed pool', () => {
  assert.equal(poolBasketKind(inputs({ shadows: [sh('a')], live: ['pa-a'] })), 'live');
});

check('poolBasketKind: proposed when a proposed pool covers the member', () => {
  const shadows = [sh('a'), sh('b')];
  assert.equal(
    poolBasketKind(inputs({ shadows, pools: [pool('p1', ['a', 'b'])] })),
    'proposed',
  );
});

check('poolBasketKind: proposed wins over live for the same member', () => {
  const shadows = [sh('a'), sh('b')];
  assert.equal(
    poolBasketKind(inputs({ shadows, pools: [pool('p1', ['a', 'b'])], live: ['pa-a', 'pa-b'] })),
    'proposed',
  );
});

check('poolBasketKind: mixed when one member is proposed-pooled and another live', () => {
  const shadows = [sh('a'), sh('b'), sh('c')];
  assert.equal(
    poolBasketKind(inputs({ shadows, pools: [pool('p1', ['a', 'b'])], live: ['pa-c'] })),
    'mixed',
  );
});

console.log(`
${checks} checks passed`);

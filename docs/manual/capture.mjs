// ============================================================================
// User-manual screenshot harness — captures every sidebar page via Playwright.
//
// Prereqs:  frontend dev server on :3000 (vite proxy → backend :8053),
//           demo data seeded (see database/seed/), TestMNC corporate present.
// Run:      node docs/manual/capture.mjs            (all pages)
//           node docs/manual/capture.mjs forecasting sweeping   (subset)
//
// Outputs:  docs/manual/img/<pageId>.png   full-page screenshots
//           docs/manual/manifest.json      per-page UI inventory (headings,
//                                          buttons, table columns) used to
//                                          ground USER_MANUAL.md in what the
//                                          UI actually shows.
//
// SECTIONS mirrors frontend/src/config/navigation.tsx — keep in sync when the
// sidebar IA changes. Page ids are the `href` values (= App.tsx PageType),
// resolved through the ?page= dev deep-link in App.tsx.
// ============================================================================

import { createRequire } from 'module';
import { mkdirSync, writeFileSync } from 'fs';
import { dirname, join } from 'path';
import { fileURLToPath } from 'url';

// Playwright lives in frontend/node_modules — resolve it from there so this
// script can stay next to the manual sources it feeds.
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium } = require('playwright');

const BASE = process.env.MANUAL_BASE_URL || 'http://localhost:3000';
const CORPORATE_ID = '11111111-1111-1111-1111-111111111111'; // TestMNC — the seeded demo corporate
const OUT = join(dirname(fileURLToPath(import.meta.url)), 'img');

const SECTIONS = [
  { title: 'Overview', pages: [
    ['dashboard', 'Dashboard'],
    ['multi-bank-liquidity', 'Multi-Bank Liquidity'],
    ['statements', 'Statements'],
  ]},
  { title: 'Accounts & Structure', pages: [
    ['accounts', 'Virtual Accounts'],
    ['physical-accounts', 'Bank Accounts'],
    ['viban', 'VIBAN Management'],
    ['account-attachments', 'Account Linking'],
    ['programs', 'Programs'],
    ['hierarchy', 'Balance Hierarchy'],
    ['entity-balance-tree', 'Entity Balance Tree'],
    ['shadow-accounts', 'Shadow Accounts'],
    ['currency-mirrors', 'Currency Mirrors'],
    ['hierarchy-operations', 'Reorganization'],
  ]},
  { title: 'Parties & Entities', pages: [
    ['legal-entities', 'Legal Entities'],
    ['parties', 'Parties & Counterparties'],
  ]},
  { title: 'Payments & Collections', pages: [
    ['transactions', 'All Transactions'],
    ['transfers', 'Transfers'],
    ['receivables', 'Receivables (AR)'],
    ['payables', 'Payables (AP)'],
    ['intercompany-pobo', 'POBO Payments'],
    ['intercompany-cobo', 'COBO Collections'],
    ['iso20022', 'ISO 20022 Payments'],
    ['settlement-vas', 'Settlement VAs'],
    ['exceptions', 'Exceptions'],
  ]},
  { title: 'Liquidity Management', pages: [
    ['forecasting', 'Cash Forecast'],
    ['sweeping', 'Cash Concentration'],
    ['simulator', 'Simulator'],
    ['notional-pooling', 'Notional Pooling'],
    ['ihb', 'In-House Bank'],
    ['netting-enhanced', 'Netting Cycles'],
    ['intercompany', 'Intercompany Dashboard'],
    ['fx-rates', 'FX Rates'],
  ]},
  { title: 'Credit & Interest', pages: [
    ['credit-limits', 'Credit Limits'],
    ['interest-config', 'Interest Configuration'],
    ['interest-accruals', 'Interest Accruals'],
  ]},
  { title: 'Specialty Programs', pages: [
    ['escrow', 'Escrow'],
    ['wallet', 'Wallets'],
    ['ecommerce-collections', 'E-Commerce Collections'],
    ['seller-collections', 'Seller Collections'],
  ]},
  { title: 'Administration', pages: [
    ['tax-charges', 'Tax & Charges'],
    ['integrations', 'Integrations'],
    ['settings', 'Settings'],
  ]},
];

// ----------------------------------------------------------------------------
// Per-page interaction states — each entry re-loads the page, clicks the
// button(s) matching the regex(es) in order, settles, and captures
// img/<pageId>__<name>.png. ONLY tab-switches and modal-openers belong here;
// never actions that mutate data (Run Settlement, Sync, Submit, …).
// `modal: true` captures the viewport instead of the full page (modals are
// position:fixed and would float mid-page on a full-height shot).
// ----------------------------------------------------------------------------
const STATES = {
  'multi-bank-liquidity': [
    { name: 'by-bank', click: ['^By Bank$'] },
    { name: 'by-currency', click: ['^By Currency$'] },
  ],
  statements: [
    { name: 'notifications', click: ['camt\\.054'] },
    { name: 'aggregated', click: ['^Aggregated Statement$'] },
  ],
  accounts: [
    { name: 'tree', click: ['^Tree$'] },
    { name: 'filters', click: ['^Filters$'] },
  ],
  viban: [
    { name: 'pools', click: ['^Pools'] },
    { name: 'vibans', click: ['^VIBANs'] },
  ],
  'account-attachments': [
    { name: 'new-attachment', click: ['^New Attachment$'], modal: true },
  ],
  programs: [
    { name: 'new-program', click: ['^New Program$'], modal: true },
  ],
  'hierarchy-operations': [
    { name: 'relocate', click: ['^Relocate Account'], modal: true },
    { name: 'acquisition', click: ['^Acquisition'], modal: true },
  ],
  transactions: [
    { name: 'ledger', click: ['^Ledger$'] },
    { name: 'filters', click: ['^Filters$'] },
  ],
  transfers: [
    { name: 'history', click: ['^Transfer History$'] },
  ],
  receivables: [
    { name: 'vibans', click: ['^VIBANs'] },
    { name: 'create-invoice', click: ['^Create Invoice$'], modal: true },
  ],
  payables: [
    { name: 'pending', click: ['^Pending'] },
    { name: 'new-payable', click: ['^New Payable$'], modal: true },
  ],
  'intercompany-pobo': [
    { name: 'overview', click: ['^Overview$'] },
    { name: 'settlement', click: ['^Settlement$'] },
    { name: 'entity-pairs', click: ['^Entity Pairs'] },
    { name: 'new-payment', click: ['^New POBO Payment$'], modal: true },
  ],
  'intercompany-cobo': [
    { name: 'setup', click: ['^Setup COBO Collection$'], modal: true },
  ],
  iso20022: [
    { name: 'outward', click: ['^Outward Payment$'] },
    { name: 'bulk', click: ['^Bulk Payment$'] },
    { name: 'status', click: ['^Payment Status$'] },
  ],
  'settlement-vas': [
    { name: 'exceptions', click: ['^Exception'] },
  ],
  forecasting: [
    { name: '30d', click: ['^30d$'] },
    { name: '12m', click: ['^12m$'] },
  ],
  sweeping: [
    { name: 'history', click: ['^Execution History'] },
  ],
  simulator: [
    { name: 'new-scenario', click: ['^New scenario$'], modal: true },
  ],
  'notional-pooling': [
    { name: 'members', click: ['^View Members'] },
  ],
  ihb: [
    { name: 'entities', click: ['^IHB Entities'] },
    { name: 'current-accounts', click: ['^Current Accounts'] },
    { name: 'loans', click: ['^Loans'] },
    { name: 'new-loan', click: ['^New Loan$'], modal: true },
  ],
  'netting-enhanced': [
    { name: 'details', click: ['^Details$'] },
  ],
  'credit-limits': [
    { name: 'gbp', click: ['^GBP'] },
  ],
  'interest-config': [
    { name: 'new-config', click: ['^New Config$'], modal: true },
  ],
  escrow: [
    { name: 'disputed', click: ['^Disputed'] },
    { name: 'new-contract', click: ['^New Contract$'], modal: true },
  ],
  wallet: [
    { name: 'programs', click: ['Programs\\s*2'] }, // the "Programs (2)" tab, not the sidebar item
    { name: 'issue', click: ['^Issue Wallet$'], modal: true },
  ],
  'seller-collections': [
    { name: 'settlements', click: ['^Settlements'] },
  ],
  'tax-charges': [
    { name: 'charges', click: ['^Charge Configurations'] },
    { name: 'jurisdictions', click: ['^Jurisdictions'] },
  ],
  integrations: [
    { name: 'connectors', click: ['^Available Connectors'] },
    { name: 'sync-history', click: ['^Sync History'] },
  ],
};

const only = new Set(process.argv.slice(2)); // empty = all

async function settle(page, id) {
  // Settle: spinners gone + a short paint delay for charts/animations.
  await page.waitForFunction(
    () => document.querySelectorAll('.animate-spin').length === 0,
    { timeout: 15000 }
  ).catch(() => console.warn(`  ⚠ ${id}: spinner still visible after 15s — capturing anyway`));
  await page.waitForTimeout(1200);
}

async function capturePage(page, id, title) {
  await page.goto(`${BASE}/?page=${id}`, { waitUntil: 'networkidle', timeout: 45000 });
  await settle(page, id);

  await page.screenshot({ path: join(OUT, `${id}.png`), fullPage: true });

  // UI inventory for the manual writer.
  const info = await page.evaluate(() => {
    const clean = (el) => (el.textContent || '').trim().replace(/\s+/g, ' ');
    const uniq = (a) => [...new Set(a.filter(Boolean))];
    const main = document.querySelector('main') || document.body;
    const visible = (el) => el.getClientRects().length > 0;
    return {
      headings: uniq([...main.querySelectorAll('h1,h2,h3')].map(clean)).slice(0, 25),
      buttons: uniq([...main.querySelectorAll('button')].filter(visible).map(clean))
        .filter((t) => t && t.length < 45).slice(0, 40),
      tableColumns: uniq([...main.querySelectorAll('th')].map(clean)).slice(0, 30),
      tabs: uniq([...main.querySelectorAll('[role="tab"]')].map(clean)).slice(0, 15),
      textPreview: clean(main).slice(0, 400),
    };
  });
  return { id, title, ...info };
}

const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
await context.addInitScript(`
  localStorage.setItem('current_corporate_id', '${CORPORATE_ID}');
`);

mkdirSync(OUT, { recursive: true });
const page = await context.newPage();
const manifest = [];
let failed = 0;

// Capture one interaction state: fresh page load → click sequence → shot.
// Fresh goto per state keeps states independent (a stuck modal can't bleed
// into the next capture).
async function captureState(page, id, state) {
  await page.goto(`${BASE}/?page=${id}`, { waitUntil: 'networkidle', timeout: 45000 });
  await settle(page, id);
  for (const pattern of state.click) {
    // Tabs vs buttons varies per page — match either role. Buttons often
    // carry JSX whitespace/icon text around the label, so soften ^/$ anchors
    // to tolerate surrounding whitespace.
    const re = new RegExp(
      pattern.replace(/^\^/, '^\\s*').replace(/\$$/, '\\s*$')
    );
    await page.locator('button:visible, [role="tab"]:visible')
      .filter({ hasText: re }).first()
      .click({ timeout: 8000 });
    await page.waitForTimeout(400);
  }
  await settle(page, id);
  const file = `${id}__${state.name}.png`;
  await page.screenshot({ path: join(OUT, file), fullPage: !state.modal });
  const info = await page.evaluate(() => {
    const clean = (el) => (el.textContent || '').trim().replace(/\s+/g, ' ');
    const uniq = (a) => [...new Set(a.filter(Boolean))];
    // Modals render in portals outside <main>; scan the whole body but prefer
    // the topmost dialog when one is open.
    const dialog = [...document.querySelectorAll('[role="dialog"], .modal, [class*="Modal"]')].at(-1);
    const root = dialog || document.querySelector('main') || document.body;
    const visible = (el) => el.getClientRects().length > 0;
    return {
      headings: uniq([...root.querySelectorAll('h1,h2,h3,h4')].map(clean)).slice(0, 20),
      buttons: uniq([...root.querySelectorAll('button')].filter(visible).map(clean))
        .filter((t) => t && t.length < 45).slice(0, 30),
      fields: uniq([...root.querySelectorAll('label')].map(clean))
        .filter((t) => t && t.length < 60).slice(0, 25),
      tableColumns: uniq([...root.querySelectorAll('th')].map(clean)).slice(0, 20),
      textPreview: clean(root).slice(0, 400),
    };
  });
  return { id: `${id}__${state.name}`, parent: id, state: state.name, ...info };
}

for (const section of SECTIONS) {
  for (const [id, title] of section.pages) {
    if (only.size && !only.has(id)) continue;
    try {
      const entry = await capturePage(page, id, title);
      entry.section = section.title;
      manifest.push(entry);
      console.log(`OK  ${id}  (${entry.buttons.length} buttons, ${entry.tableColumns.length} cols)`);
    } catch (e) {
      failed++;
      console.error(`FAIL ${id}: ${e.message.split('\n')[0]}`);
    }
    for (const state of STATES[id] || []) {
      try {
        const entry = await captureState(page, id, state);
        entry.section = section.title;
        manifest.push(entry);
        console.log(`  ok ${id}__${state.name}`);
      } catch (e) {
        failed++;
        console.error(`  FAIL ${id}__${state.name}: ${e.message.split('\n')[0]}`);
      }
    }
  }
}

await browser.close();
// Only a full run may write the manifest — a subset run would clobber the
// other pages' entries.
if (!only.size) {
  writeFileSync(join(dirname(OUT), 'manifest.json'), JSON.stringify(manifest, null, 2));
}
console.log(`\n${manifest.length} captured, ${failed} failed → ${OUT}`);
process.exit(failed ? 1 : 0);

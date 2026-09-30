/**
 * Records the receivable → dynamic VIBAN → QR flow as a short video.
 *
 * The story: a merchant raises an invoice for a service, ticks "Generate VIBAN",
 * and the invoice comes back with its own virtual IBAN plus a QR/link to hand the
 * customer. Paying that VIBAN auto-reconciles to the invoice — the per-invoice
 * account IS the reconciliation key.
 *
 * Note on the QR: it encodes the payment-link URL, so scanning opens the payer
 * page. It is not an EMVCo (UPI/AANI) scan-to-pay payload — narrate accordingly.
 *
 * Prerequisites (the script checks and fails loudly rather than filming a dud):
 *   1. frontend on :3000          → preview_start "frontend"
 *   2. backend on :8053, UAE      → preview_start "backend-uae"   (UK gives a GB IBAN)
 *   3. a customer named "Gulf Freight Services" with the CUSTOMER role
 *
 * Usage:  node scripts/record-receivable-qr-demo.mjs [--headed]
 * Output: demo/receivable-qr-demo.webm
 */
import { chromium } from 'playwright';
import { mkdirSync, readdirSync, renameSync, statSync } from 'node:fs';
import { join } from 'node:path';

const BASE = process.env.DEMO_BASE_URL ?? 'http://localhost:3000';
const OUT_DIR = 'demo';
const OUT_NAME = 'receivable-qr-demo.webm';
const SIZE = { width: 1000, height: 640 };

const CUSTOMER = 'Gulf Freight Services';
const AMOUNT = '4850';
const DESCRIPTION = 'Freight forwarding - 12 pallets, Jebel Ali to Dubai South';
const COLLECTION_ACCOUNT = 'Main Operating Account';

// Beat pacing. Playwright clicks faster than anyone can read, so every step is
// held deliberately — the highlight is what the viewer is meant to follow.
const HOLD = 900;   // how long an element stays lit
const BEAT = 700;   // pause after an action lands
const READ = 2200;  // pause on something worth actually reading

const log = (msg) => console.log(`  ${msg}`);

/** Outline styles, re-injected after every navigation (a load clears them). */
async function ensureStyles(page) {
  await page.addStyleTag({
    content: `
      .demo-spot {
        outline: 3px solid #e8a33d !important;
        outline-offset: 3px;
        border-radius: 10px;
        box-shadow: 0 0 0 6px rgba(232,163,61,.18) !important;
      }
    `,
  });
}

/** Briefly light up an element so the viewer's eye lands where the action is. */
async function spotlight(page, locator, ms = HOLD) {
  await locator.scrollIntoViewIfNeeded();
  await locator.evaluate((el) => el.classList.add('demo-spot'));
  await page.waitForTimeout(ms);
  await locator.evaluate((el) => el.classList.remove('demo-spot'));
}

/** Highlight, then click. */
async function showAndClick(page, locator, ms = HOLD) {
  await spotlight(page, locator, ms);
  await locator.click();
  await page.waitForTimeout(BEAT);
}

/** Type into a field at a readable speed, with the field lit while it fills. */
async function showAndType(page, locator, text, delay = 28) {
  await locator.scrollIntoViewIfNeeded();
  await locator.evaluate((el) => el.classList.add('demo-spot'));
  await locator.click();
  await locator.fill('');
  await locator.type(text, { delay });
  await page.waitForTimeout(BEAT);
  await locator.evaluate((el) => el.classList.remove('demo-spot'));
}

mkdirSync(OUT_DIR, { recursive: true });

const browser = await chromium.launch({ headless: !process.argv.includes('--headed') });
const context = await browser.newContext({
  viewport: SIZE,
  recordVideo: { dir: OUT_DIR, size: SIZE },
  deviceScaleFactor: 1,          // keep the file small; retina doubles it for nothing
});
const page = await context.newPage();

try {
  // --- Beat 1: the receivables list -----------------------------------------
  // Entered from the list, never ?page=receivables-create directly: reached
  // without params the page falls back to a corporate id that is not in the
  // database and the save dies on a foreign key.
  log('1/8  Receivables list');
  await page.goto(`${BASE}/?page=receivables`, { waitUntil: 'domcontentloaded' });
  await ensureStyles(page);
  const createBtn = page.locator('main button, header button').filter({ hasText: 'Create Invoice' }).first();
  await createBtn.waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(READ);
  await showAndClick(page, createBtn);

  // --- Beat 2: pick the customer --------------------------------------------
  log('2/8  Customer');
  const search = page.locator('main input[placeholder*="Search customer" i]');
  await search.waitFor({ state: 'visible', timeout: 20_000 });
  await ensureStyles(page);
  await showAndType(page, search, 'Gulf');

  const customerHit = page.locator('main button').filter({ hasText: CUSTOMER }).first();
  await customerHit.waitFor({ state: 'visible', timeout: 15_000 });
  await showAndClick(page, customerHit);

  // --- Beat 3: what is being billed -----------------------------------------
  log('3/8  Amount + description');
  await showAndType(page, page.locator('main input[type="number"]').first(), AMOUNT, 70);
  await showAndType(page, page.locator('main textarea').first(), DESCRIPTION, 14);

  // --- Beat 4: where the money lands ----------------------------------------
  log('4/8  Collection account');
  await showAndClick(
    page,
    page.locator('main button').filter({ hasText: COLLECTION_ACCOUNT }).first(),
  );

  // --- Beat 5: the point of the demo ----------------------------------------
  log('5/8  Generate VIBAN');
  await showAndClick(
    page,
    page.locator('main button').filter({ hasText: /^Generate$/ }).first(),
    HOLD + 400,
  );

  // --- Beat 6: the summary says it will be minted on save -------------------
  log('6/8  Summary');
  const summary = page.locator('main').getByText('Will generate on save', { exact: false }).first();
  await summary.waitFor({ state: 'visible', timeout: 10_000 });
  await spotlight(page, summary, READ);

  // --- Beat 7: create, and the QR comes back --------------------------------
  log('7/8  Create → QR');
  const submit = page.locator('main button').filter({ hasText: 'Create Invoice' }).first();
  await submit.scrollIntoViewIfNeeded();
  if (await submit.isDisabled()) throw new Error('Create Invoice is disabled — a required field is unfilled');
  await showAndClick(page, submit);

  const dialog = page.locator('[role="dialog"]');
  await dialog.waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(BEAT);

  const paymentLink = (await dialog.locator('code').first().innerText()).trim();
  if (!/token=/.test(paymentLink)) throw new Error(`No payment token in link: ${paymentLink}`);
  log(`     ${paymentLink}`);

  await spotlight(page, dialog.locator('code').first(), READ);
  await spotlight(page, dialog.locator('svg').last(), READ);   // the QR itself

  // --- Beat 8: the payer's view, with its own VIBAN -------------------------
  log('8/8  Payer page');
  await page.goto(paymentLink, { waitUntil: 'domcontentloaded' });
  await ensureStyles(page);

  const iban = page.getByText(/\b[A-Z]{2}\d{2}[A-Z0-9]{12,}\b/).first();
  await iban.waitFor({ state: 'visible', timeout: 20_000 });
  const ibanText = (await iban.innerText()).trim();
  log(`     VIBAN ${ibanText}`);
  if (!ibanText.startsWith('AE')) {
    log(`     ! not an AE IBAN — backend is probably on the UK profile`);
  }
  await page.waitForTimeout(READ);
  await spotlight(page, iban, READ);
  await page.waitForTimeout(READ);

  log('done');
} finally {
  // The video is only written out when the context closes.
  await context.close();
  await browser.close();
}

// Playwright names videos by a random id; give it one worth attaching to a PR.
const newest = readdirSync(OUT_DIR)
  .filter((f) => f.endsWith('.webm') && f !== OUT_NAME)
  .map((f) => ({ f, t: statSync(join(OUT_DIR, f)).mtimeMs }))
  .sort((a, b) => b.t - a.t)[0];
if (newest) {
  renameSync(join(OUT_DIR, newest.f), join(OUT_DIR, OUT_NAME));
  const kb = Math.round(statSync(join(OUT_DIR, OUT_NAME)).size / 1024);
  console.log(`\n${join(OUT_DIR, OUT_NAME)}  (${kb} kB)`);
}

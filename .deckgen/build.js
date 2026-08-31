'use strict';
// Aperture × ABN AMRO — white-label corporate cash-management pitch deck.
// v2: 6 vetted live screenshots + 3 concept slides (data-sparse capabilities),
// duplicate merged, narrative/contrast fixes per fresh-eyes QA.
const path = require('path');
const PptxGenJS = require('pptxgenjs');

const A = path.join(__dirname, 'assets');
const img = f => path.join(A, f);

// ─── Palette (Midnight Executive — mirrors the real Aperture product chrome) ──
const INK   = '0B1B2B';
const NAVY  = '102A43';
const GOLD  = 'C1842B';
const GOLDS = 'E7C887';
const PAPER = 'FBFAF7';
const WHITE = 'FFFFFF';
const BODY  = '2B2B2B';
const MUTE  = '6B7280';
const LINE  = 'DAD6CC';

const HEAD = 'Georgia';
const BODYF = 'Calibri';

const pres = new PptxGenJS();
pres.defineLayout({ name: 'W', width: 13.333, height: 7.5 });
pres.layout = 'W';
pres.author = 'Aperture';
pres.company = 'Aperture';
pres.title = 'Aperture — Corporate Cash-Management Portal for ABN AMRO';
const PW = 13.333, PH = 7.5;

const shadow = () => ({ type: 'outer', color: '0B1B2B', blur: 9, offset: 3, angle: 135, opacity: 0.18 });
let pageNo = 0;

function footer(s) {
  pageNo += 1;
  s.addText('APERTURE  ×  ABN AMRO', { x: 0.55, y: PH - 0.42, w: 5, h: 0.3, fontFace: BODYF, fontSize: 8, color: MUTE, charSpacing: 2 });
  s.addText('Confidential — prepared for ABN AMRO', { x: PW - 5.55, y: PH - 0.42, w: 4.4, h: 0.3, fontFace: BODYF, fontSize: 8, color: MUTE, align: 'right' });
  s.addText(String(pageNo).padStart(2, '0'), { x: PW - 1.0, y: PH - 0.42, w: 0.45, h: 0.3, fontFace: BODYF, fontSize: 9, color: NAVY, align: 'right', bold: true });
}

function head(s, kicker, title, titleW) {
  s.addShape(pres.shapes.RECTANGLE, { x: 0.55, y: 0.5, w: 0.14, h: 0.14, fill: { color: GOLD } });
  s.addText(kicker.toUpperCase(), { x: 0.8, y: 0.44, w: 9, h: 0.28, fontFace: BODYF, fontSize: 11, color: GOLD, bold: true, charSpacing: 3, margin: 0 });
  s.addText(title, { x: 0.53, y: 0.78, w: titleW || 12, h: 0.95, fontFace: HEAD, fontSize: 29, color: NAVY, bold: true, margin: 0, lineSpacing: 32 });
}

const bg = (s, color) => { s.background = { color }; };

function bullets(s, items, opt) {
  const o = Object.assign({ x: 8.0, y: 2.0, w: 4.8, h: 4.3, fontSize: 14 }, opt || {});
  s.addText(items.map(t => ({
    text: t, options: { bullet: { code: '2022', indent: 18 }, color: BODY, breakLine: true, paraSpaceAfter: 12 },
  })), { x: o.x, y: o.y, w: o.w, h: o.h, fontFace: BODYF, fontSize: o.fontSize, color: BODY, valign: 'top' });
}

function shot(s, file, x, y, w) {
  const h = w / 1.6;
  s.addShape(pres.shapes.RECTANGLE, { x: x - 0.05, y: y - 0.05, w: w + 0.1, h: h + 0.1, fill: { color: WHITE }, line: { color: NAVY, width: 1 }, shadow: shadow() });
  s.addImage({ path: img(file), x, y, w, h });
  return h;
}

function card(s, x, y, w, h, title, desc) {
  s.addShape(pres.shapes.RECTANGLE, { x, y, w, h, fill: { color: WHITE }, line: { color: LINE, width: 1 }, shadow: shadow() });
  s.addShape(pres.shapes.RECTANGLE, { x: x + 0.22, y: y + 0.22, w: 0.12, h: 0.12, fill: { color: GOLD } });
  s.addText(title, { x: x + 0.22, y: y + 0.4, w: w - 0.44, h: 0.4, fontFace: HEAD, fontSize: 14.5, color: NAVY, bold: true, margin: 0 });
  s.addText(desc, { x: x + 0.22, y: y + 0.8, w: w - 0.44, h: h - 0.98, fontFace: BODYF, fontSize: 11.5, color: BODY, margin: 0, valign: 'top' });
}

const cap = (s, x, y, w, txt) => s.addText(txt, { x, y, w, h: 0.32, fontFace: BODYF, fontSize: 10.5, italic: true, color: MUTE, margin: 0 });

// ───────────────────────────── 1 · TITLE ─────────────────────────────
(() => {
  const s = pres.addSlide(); bg(s, INK);
  s.addShape(pres.shapes.RECTANGLE, { x: 0, y: 0, w: PW, h: 0.18, fill: { color: GOLD } });
  s.addShape(pres.shapes.RECTANGLE, { x: 0.9, y: 1.55, w: 0.34, h: 0.34, fill: { color: GOLD } });
  s.addText('A', { x: 0.9, y: 1.55, w: 0.34, h: 0.34, fontFace: HEAD, fontSize: 18, color: INK, bold: true, align: 'center', valign: 'middle', margin: 0 });
  s.addText('APERTURE  ·  TREASURY INTELLIGENCE', { x: 1.4, y: 1.58, w: 8, h: 0.32, fontFace: BODYF, fontSize: 13, color: GOLDS, bold: true, charSpacing: 4, margin: 0 });
  s.addText('A cash-management portal\nABN AMRO can call its own.', { x: 0.85, y: 2.5, w: 11.5, h: 2.2, fontFace: HEAD, fontSize: 44, color: WHITE, bold: true, lineSpacing: 48, margin: 0 });
  s.addText('White-label corporate digital banking — multi-bank cash, liquidity, payments and FX, delivered on ABN AMRO rails, under ABN AMRO’s brand.', { x: 0.9, y: 4.7, w: 8.2, h: 1.05, fontFace: BODYF, fontSize: 15, color: 'C9D4DF', margin: 0, lineSpacing: 22 });
  s.addShape(pres.shapes.RECTANGLE, { x: 0.92, y: 5.95, w: 3.0, h: 0.03, fill: { color: GOLD } });
  s.addText('Prepared for ABN AMRO  ·  Corporate & Transaction Banking', { x: 0.9, y: 6.1, w: 8, h: 0.3, fontFace: BODYF, fontSize: 11.5, color: 'AEBCC9', margin: 0 });
  s.addText('Confidential discussion document  ·  18 May 2026', { x: 0.9, y: 6.4, w: 8, h: 0.3, fontFace: BODYF, fontSize: 11.5, color: 'AEBCC9', margin: 0 });
  s.addText('See every flow,\nevery account,\nevery entity.', { x: 9.7, y: 5.5, w: 3.0, h: 1.4, fontFace: HEAD, fontSize: 16, italic: true, color: GOLDS, align: 'right', lineSpacing: 20, margin: 0 });
})();

// ───────────────────── 2 · THE SHIFT ─────────────────────
(() => {
  const s = pres.addSlide(); bg(s, PAPER);
  head(s, 'Why this conversation', 'Treasury moved on. The portal is now the relationship.', 11.8);
  s.addText('Corporate treasurers no longer live in bank statements and file uploads. They live in a screen — and the bank that owns that screen owns the data, the flow, and the next mandate.', { x: 0.55, y: 1.75, w: 12.2, h: 0.7, fontFace: BODYF, fontSize: 14, color: BODY, italic: true, margin: 0 });
  const colW = 5.95, y = 2.7, h = 3.7;
  s.addShape(pres.shapes.RECTANGLE, { x: 0.55, y, w: colW, h, fill: { color: WHITE }, line: { color: LINE, width: 1 }, shadow: shadow() });
  s.addText('YESTERDAY — BANK-CENTRIC', { x: 0.85, y: y + 0.28, w: colW - 0.6, h: 0.3, fontFace: BODYF, fontSize: 12, bold: true, color: MUTE, charSpacing: 2, margin: 0 });
  s.addText([
    { text: 'One bank, one portal, one set of balances', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 10 } },
    { text: 'End-of-day statements, manual consolidation', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 10 } },
    { text: 'Treasurer stitches the picture in spreadsheets', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 10 } },
    { text: 'The bank is a utility, easily swapped', options: { bullet: { code: '2022' } } },
  ], { x: 0.85, y: y + 0.68, w: colW - 0.6, h: h - 0.95, fontFace: BODYF, fontSize: 13, color: BODY, valign: 'top' });
  s.addShape(pres.shapes.RECTANGLE, { x: 0.55 + colW + 0.35, y, w: colW, h, fill: { color: NAVY }, shadow: shadow() });
  s.addText('TODAY — TREASURER-CENTRIC', { x: 0.85 + colW + 0.35, y: y + 0.28, w: colW - 0.6, h: 0.3, fontFace: BODYF, fontSize: 12, bold: true, color: GOLDS, charSpacing: 2, margin: 0 });
  s.addText([
    { text: 'Every bank, every account, one live cockpit', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 10 } },
    { text: 'Real-time positions, FX-honest, self-serve', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 10 } },
    { text: 'The portal does the synthesis, not the treasurer', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 10 } },
    { text: 'The portal is the relationship — and it is sticky', options: { bullet: { code: '2022' } } },
  ], { x: 0.85 + colW + 0.35, y: y + 0.68, w: colW - 0.6, h: h - 0.95, fontFace: BODYF, fontSize: 13, color: 'E8EEF3', valign: 'top' });
  footer(s);
})();

// ─────────────── 3 · THE ABN AMRO OPPORTUNITY ───────────────
(() => {
  const s = pres.addSlide(); bg(s, PAPER);
  head(s, 'The opportunity', 'Where this leaves ABN AMRO', 11);
  s.addText([
    { text: 'Forces on your corporate franchise', options: { fontFace: HEAD, fontSize: 16, color: NAVY, bold: true, breakLine: true, paraSpaceAfter: 14 } },
    { text: 'Clients fragment cash across many banks — including competitors', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 11 } },
    { text: 'Expectations are set by fintech-grade UX, not legacy host-to-host', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 11 } },
    { text: 'Building a modern portal in-house is multi-year and high-risk', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 11 } },
    { text: 'TMS vendors & aggregators are quietly disintermediating the bank', options: { bullet: { code: '2022' } } },
  ], { x: 0.55, y: 1.95, w: 6.6, h: 4.4, fontFace: BODYF, fontSize: 14, color: BODY, valign: 'top' });
  const x = 7.5, y = 1.95, w = 5.25, h = 4.45;
  s.addShape(pres.shapes.RECTANGLE, { x, y, w, h, fill: { color: NAVY }, shadow: shadow() });
  s.addShape(pres.shapes.RECTANGLE, { x, y, w: 0.1, h, fill: { color: GOLD } });
  s.addText('THE PLAY', { x: x + 0.4, y: y + 0.35, w: w - 0.7, h: 0.3, fontFace: BODYF, fontSize: 12, bold: true, color: GOLDS, charSpacing: 3, margin: 0 });
  s.addText('Give the treasurer the best multi-bank cockpit on the market — and put ABN AMRO’s name on it.', { x: x + 0.4, y: y + 0.78, w: w - 0.7, h: 1.5, fontFace: HEAD, fontSize: 19, color: WHITE, bold: true, margin: 0, lineSpacing: 24 });
  s.addText('The portal becomes the client’s daily home. Retention, primary-bank status, and new digital fee revenue follow the screen.', { x: x + 0.4, y: y + 2.55, w: w - 0.7, h: 1.6, fontFace: BODYF, fontSize: 13, color: 'D7DEE6', margin: 0, lineSpacing: 20 });
  footer(s);
})();

// ─────────────── 4 · INTRODUCING APERTURE ───────────────
(() => {
  const s = pres.addSlide(); bg(s, PAPER);
  head(s, 'Introducing Aperture', 'A portal you deploy — not a project you start', 11.5);
  s.addText('Aperture is a production-ready corporate digital-banking platform with a cash-management core, designed from day one to be white-labelled and multi-tenant.', { x: 0.55, y: 1.85, w: 12.1, h: 0.85, fontFace: BODYF, fontSize: 15, color: BODY, margin: 0, lineSpacing: 22 });
  const items = [
    ['Production-ready', 'A working platform today — not slideware. Live multi-bank data, real liquidity engines.'],
    ['White-label & multi-tenant', 'Your brand, your domain, your clients — isolated per corporate, themed to ABN AMRO.'],
    ['Cash-management core', 'Positions, liquidity, payments, FX and intercompany — the treasurer’s full mandate.'],
    ['Multi-bank by design', 'Aggregates ABN AMRO and third-party banks — the view no single bank can offer.'],
  ];
  const cw = 2.92, gap = 0.22, x0 = 0.55, cy = 3.0, ch = 3.35;
  items.forEach((it, i) => card(s, x0 + i * (cw + gap), cy, cw, ch, it[0], it[1]));
  footer(s);
})();

// ─────────────── 5 · HERO (dashboard) — merged trust message ───────────────
(() => {
  const s = pres.addSlide(); bg(s, WHITE);
  head(s, 'The big idea', 'Own the corporate treasurer’s morning', 12);
  const h = shot(s, 'dashboard.png', 0.55, 1.85, 7.15);
  cap(s, 0.55, 1.85 + h + 0.14, 7.15, 'ABN AMRO–branded Cash Position cockpit — the first screen the treasurer opens, every day.');
  bullets(s, [
    'The daily landing: consolidated cash, action queue, FX — live across every bank.',
    'A position treasurers trust: per-currency truth, no synthetic cross-currency total.',
    'Live freshness per account — stale balances flagged before they bite.',
    'Every glance reinforces the ABN AMRO relationship.',
  ], { x: 8.0, y: 2.0, w: 4.8, fontSize: 13.5 });
  footer(s);
})();

// ─────────────── 6 · CAPABILITY MAP ───────────────
(() => {
  const s = pres.addSlide(); bg(s, PAPER);
  head(s, 'Scope', 'One portal, the whole cash-management mandate', 12);
  const caps = [
    ['Cash visibility', 'Consolidated, FX-honest position across every bank & currency.'],
    ['Multi-bank liquidity', 'Balances, freshness and distribution across all banks — incl. competitors.'],
    ['Payments & approvals', 'ISO 20022 payments, POBO/COBO, multi-level approval workspace.'],
    ['Liquidity structures', 'Sweeps, pooling, in-house bank, netting — modelled before you commit.'],
    ['FX', 'Sourced, executable rates in the treasurer’s flow, next to the position.'],
    ['Treasury intelligence', 'Natural-language assistant over positions, sweeps and statements.'],
  ];
  const cw = 3.95, ch = 1.95, gx = 0.55, gy = 1.95, gpx = 0.22, gpy = 0.22;
  caps.forEach((c, i) => {
    const col = i % 3, row = Math.floor(i / 3);
    card(s, gx + col * (cw + gpx), gy + row * (ch + gpy), cw, ch, c[0], c[1]);
  });
  footer(s);
})();

// screenshot capability slide
function shotSlide(kicker, title, file, blist, capt) {
  const s = pres.addSlide(); bg(s, WHITE);
  head(s, kicker, title, 12);
  const h = shot(s, file, 0.55, 1.85, 7.15);
  cap(s, 0.55, 1.85 + h + 0.14, 7.15, capt);
  bullets(s, blist, { x: 8.0, y: 2.0, w: 4.8, fontSize: 14 });
  footer(s);
}

// concept capability slide (no screenshot — for capabilities whose live demo
// data is sparse; honest by construction: no fabricated figures, no zeros)
function conceptSlide(kicker, title, points, punchTitle, punch) {
  const s = pres.addSlide(); bg(s, PAPER);
  head(s, kicker, title, 8);
  const cw = 3.7, ch = 1.95, x0 = 0.55, y0 = 1.95, gx = 0.2, gy = 0.2;
  points.forEach((p, i) => {
    const col = i % 2, row = Math.floor(i / 2);
    card(s, x0 + col * (cw + gx), y0 + row * (ch + gy), cw, ch, p[0], p[1]);
  });
  const px = 8.5, pw = 4.25;
  s.addShape(pres.shapes.RECTANGLE, { x: px, y: 1.95, w: pw, h: 4.15, fill: { color: NAVY }, shadow: shadow() });
  s.addShape(pres.shapes.RECTANGLE, { x: px, y: 1.95, w: 0.1, h: 4.15, fill: { color: GOLD } });
  s.addText(punchTitle.toUpperCase(), { x: px + 0.35, y: 2.25, w: pw - 0.6, h: 0.3, fontFace: BODYF, fontSize: 12, bold: true, color: GOLDS, charSpacing: 2, margin: 0 });
  s.addText(punch, { x: px + 0.35, y: 2.65, w: pw - 0.6, h: 3.2, fontFace: BODYF, fontSize: 13.5, color: 'E8EEF3', margin: 0, lineSpacing: 21, valign: 'top' });
  footer(s);
}

// 7 · Multi-bank (shot)
shotSlide('Capability · Multi-bank liquidity', 'Every bank. Even the ones that aren’t you.', 'multibank.png', [
  'Aggregates balances across all banks — including competitors.',
  'Home-bank vs external split, value-weighted distribution.',
  'The control-tower view no single bank can show its client.',
  'Strategic effect: ABN AMRO becomes the client’s liquidity hub.',
], 'Multi-Bank Liquidity — aggregate position and cross-bank distribution.');

// 8 · Cash concentration (shot)
shotSlide('Capability · Liquidity structures', 'Cross-bank cash concentration', 'cash-concentration.png', [
  'Orchestrate sweeps across banks, not just within ABN AMRO.',
  'Rule-based: target balances, thresholds, schedules.',
  'Concentrate idle cash to where it earns or offsets.',
  'Treasurer sees what moved, what is queued, what is blocked.',
], 'Cash Concentration — cross-bank sweep orchestration.');

// 9 · Notional pooling (shot)
shotSlide('Capability · Liquidity structures', 'Notional pooling & interest optimisation', 'notional-pooling.png', [
  'Notional pools without physically moving cash.',
  'Offset debit and credit positions across entities.',
  'Interest benefit applied and visible to the treasurer.',
  'Structure-aware: entities, currencies, participants.',
], 'Notional Pooling — pool structures and interest optimisation.');

// 10 · In-house bank (shot)
shotSlide('Capability · Liquidity structures', 'In-house bank: intercompany at scale', 'in-house-bank.png', [
  'Internal loans and deposits between group entities.',
  'POBO / COBO — pay and collect on behalf of subsidiaries.',
  'Cuts external payment volume, fees and float.',
  'Group treasury becomes its own bank — on your rails.',
], 'In-House Bank — intercompany loans, deposits and positions.');

// 11 · Simulator (concept)
conceptSlide('Capability · Decision support', 'Model the structure before you commit', [
  ['Sandbox by design', 'Design shadows, sweep rules and pools on your live model — with zero live writes.'],
  ['Optimisation Score', 'Every scenario scored, FX- and charge-aware — trade-offs quantified, not guessed.'],
  ['Diff & compare', 'Diff a design against the live structure; compare scenarios side by side.'],
  ['Fork & propose', 'Fork alternatives, promote the winner via a Propose hand-off — fully audited.'],
], 'Why it matters for ABN AMRO',
  'Treasurers won’t restructure cash on a hunch. A safe, scored what-if tool turns “should we change the pool?” into an ABN AMRO-led, evidence-backed conversation — and the changes that follow run on your rails.');

// 12 · Netting (concept)
conceptSlide('Capability · Liquidity structures', 'Netting that pays for itself', [
  ['Cycle-based netting', 'Collapse many gross intercompany settlements into a few net ones.'],
  ['Fewer FX trades', 'Net exposures before they hit the market — lower spread cost.'],
  ['Fewer wires & fees', 'Each removed settlement removes a transfer charge and float.'],
  ['Auditable', 'Every cycle: participants, gross, net and saving — fully traceable.'],
], 'Why it matters for ABN AMRO',
  'A netting engine is a hard build clients won’t do alone — offering it on your portal deepens the mandate and the fee relationship, while the savings make the portal self-justifying to the client’s CFO.');

// 13 · Payments & collections (concept)
conceptSlide('Capability · Payments & collections', 'Payments, approvals, ISO 20022', [
  ['Approval workspace', 'Multi-level approvals with expiry awareness — approve/reject on the row.'],
  ['ISO 20022 native', 'pain.001 / camt / CBPR+ and SWIFT references inline.'],
  ['POBO / COBO', 'Pay and collect on behalf of subsidiaries, first-class.'],
  ['Controlled & audited', 'Limits, segregation of duties, full trail on every instruction.'],
], 'Why it matters for ABN AMRO',
  'Payments is where trust is won or lost. A controlled, standards-native workspace on ABN AMRO rails keeps high-value flow — and the data that comes with it — inside your franchise.');

// 14 · FX (concept)
conceptSlide('Capability · FX', 'FX in the treasurer’s flow', [
  ['Sourced rates', 'Reuters / central-bank / partner feeds, attributed and timestamped.'],
  ['In context', 'Rates sit next to the exposure that needs them — no app-switching.'],
  ['Honest by design', 'Only what the data supports is shown — no fabricated moves.'],
  ['Path to execute', 'From “see the exposure” to “act on it” without leaving the portal.'],
], 'Why it matters for ABN AMRO',
  'FX is a prime fee line. Surfacing dealable, sourced rates where the treasurer already works routes more flow to ABN AMRO’s desk instead of a third-party platform.');

// 15 · Treasury Copilot (shot)
shotSlide('Capability · Treasury intelligence', 'Ask treasury anything', 'copilot.png', [
  'Natural-language assistant over positions, sweeps, statements.',
  '“What’s our AED position?” · “Show failed sweeps today.”',
  'A differentiator few bank portals offer today.',
  'Preview now, with a clear roadmap to deeper automation.',
], 'Treasury Copilot — natural-language treasury assistant (preview).');

// ─────────────── 15 · BUILT BANK-GRADE ───────────────
(() => {
  const s = pres.addSlide(); bg(s, PAPER);
  head(s, 'Under the hood', 'Engineered for a bank to deploy', 12);
  const items = [
    ['Modern, supportable stack', 'Java 21 / Spring Boot 3, PostgreSQL, Docker — technology your engineering can own.'],
    ['Security & access', 'OAuth2 / Spring Security, scoped multi-tenant isolation, full audit trail.'],
    ['ISO 20022 native', 'pain / camt / CBPR+ messaging — speaks the language of your core and the network.'],
    ['Resilience: BaNCS fallback', 'Store-locally, sync-later when the core is unreachable — treasury keeps working.'],
  ];
  const cw = 2.92, gap = 0.22, x0 = 0.55, cy = 1.95, ch = 2.6;
  items.forEach((it, i) => card(s, x0 + i * (cw + gap), cy, cw, ch, it[0], it[1]));
  s.addShape(pres.shapes.RECTANGLE, { x: 0.55, y: 4.85, w: 12.2, h: 1.5, fill: { color: NAVY }, shadow: shadow() });
  s.addShape(pres.shapes.RECTANGLE, { x: 0.55, y: 4.85, w: 0.1, h: 1.5, fill: { color: GOLD } });
  s.addText('Honest by construction', { x: 0.95, y: 5.05, w: 11.5, h: 0.35, fontFace: HEAD, fontSize: 15, color: GOLDS, bold: true, margin: 0 });
  s.addText('The product refuses to fabricate numbers it cannot source — no fake cross-currency totals, no invented FX moves. For a treasurer making real decisions, that restraint is the feature.', { x: 0.95, y: 5.42, w: 11.5, h: 0.85, fontFace: BODYF, fontSize: 12.5, color: 'E8EEF3', margin: 0, lineSpacing: 18 });
  footer(s);
})();

// ─────────────── 16 · WHITE-LABEL & DEPLOY MODEL ───────────────
(() => {
  const s = pres.addSlide(); bg(s, PAPER);
  head(s, 'Deployment model', 'Your brand. Your rails. Your clients.', 12);
  const lane = (x, w, color, tcolor, t, sub) => {
    s.addShape(pres.shapes.RECTANGLE, { x, y: 2.05, w, h: 2.5, fill: { color }, line: { color: LINE, width: 1 }, shadow: shadow() });
    s.addText(t, { x: x + 0.15, y: 2.42, w: w - 0.3, h: 0.82, fontFace: HEAD, fontSize: 15, color: tcolor, bold: true, align: 'center', valign: 'middle', margin: 0, lineSpacing: 18 });
    s.addText(sub, { x: x + 0.15, y: 3.42, w: w - 0.3, h: 1.0, fontFace: BODYF, fontSize: 11, color: tcolor === WHITE ? 'D7DEE6' : MUTE, align: 'center', valign: 'top', margin: 0, lineSpacing: 15 });
  };
  lane(0.55, 3.5, WHITE, NAVY, 'ABN AMRO corporate clients', 'Treasurers & finance teams, multi-entity, multi-currency');
  s.addText('▶', { x: 4.12, y: 3.05, w: 0.5, h: 0.5, fontFace: BODYF, fontSize: 20, color: GOLD, align: 'center', margin: 0 });
  lane(4.62, 4.1, NAVY, WHITE, 'ABN AMRO-branded Aperture portal', 'White-label theme · multi-tenant · API-first · your domain');
  s.addText('▶', { x: 8.78, y: 3.05, w: 0.5, h: 0.5, fontFace: BODYF, fontSize: 20, color: GOLD, align: 'center', margin: 0 });
  lane(9.28, 3.45, WHITE, NAVY, 'ABN AMRO core / BaNCS  +  third-party banks', 'ISO 20022 · host-to-host · partner-bank APIs');
  s.addText([
    { text: 'Brandable theme & domain — it reads as an ABN AMRO product end-to-end', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 9 } },
    { text: 'Multi-tenant isolation per corporate client; scoped data and access', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 9 } },
    { text: 'API-first integration to your core, BaNCS and partner banks', options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 9 } },
    { text: 'Phased go-live: pilot cohort → expand → full rollout', options: { bullet: { code: '2022' } } },
  ], { x: 0.55, y: 4.85, w: 12.2, h: 1.55, fontFace: BODYF, fontSize: 12.5, color: BODY, valign: 'top' });
  footer(s);
})();

// ─────────────── 17 · WHY APERTURE ───────────────
(() => {
  const s = pres.addSlide(); bg(s, PAPER);
  head(s, 'The case', 'Why Aperture, and why now', 12);
  const col = (x, title, lines, hot) => {
    s.addShape(pres.shapes.RECTANGLE, { x, y: 1.95, w: 3.95, h: 3.15, fill: { color: hot ? NAVY : WHITE }, line: { color: hot ? NAVY : LINE, width: 1 }, shadow: shadow() });
    if (hot) s.addShape(pres.shapes.RECTANGLE, { x, y: 1.95, w: 3.95, h: 0.09, fill: { color: GOLD } });
    s.addText(title, { x: x + 0.25, y: 2.2, w: 3.45, h: 0.55, fontFace: HEAD, fontSize: 14.5, bold: true, color: hot ? GOLDS : NAVY, margin: 0 });
    s.addText(lines.map(t => ({ text: t, options: { bullet: { code: '2022' }, breakLine: true, paraSpaceAfter: 9 } })), { x: x + 0.25, y: 2.8, w: 3.45, h: 2.15, fontFace: BODYF, fontSize: 11.5, color: hot ? 'E8EEF3' : BODY, valign: 'top' });
  };
  col(0.55, 'Build in-house', ['Multi-year, high execution risk', 'Opportunity cost while clients churn', 'You still maintain it forever']);
  col(4.72, 'Generic TMS / aggregator', ['Not your brand — disintermediates you', 'Client loyalty shifts to the vendor', 'Limited multi-bank, your data leaks out']);
  col(8.88, 'Aperture, white-label', ['Your brand, live in months not years', 'Multi-bank cockpit clients stay in', 'Retention, primacy, new fee revenue'], true);
  s.addText('Outcome for ABN AMRO:  stickier corporate relationships  ·  primary-bank status defended  ·  faster time-to-market  ·  a new digital revenue line.', { x: 0.55, y: 5.35, w: 12.2, h: 0.9, fontFace: BODYF, fontSize: 13, italic: true, color: NAVY, margin: 0, lineSpacing: 19 });
  footer(s);
})();

// ─────────────── 18 · NEXT STEPS (closing) ───────────────
(() => {
  const s = pres.addSlide(); bg(s, INK);
  s.addShape(pres.shapes.RECTANGLE, { x: 0, y: PH - 0.18, w: PW, h: 0.18, fill: { color: GOLD } });
  s.addShape(pres.shapes.RECTANGLE, { x: 0.9, y: 0.85, w: 0.3, h: 0.3, fill: { color: GOLD } });
  s.addText('NEXT STEPS', { x: 1.35, y: 0.86, w: 6, h: 0.32, fontFace: BODYF, fontSize: 13, color: GOLDS, bold: true, charSpacing: 4, margin: 0 });
  s.addText('Let’s put ABN AMRO’s name on it.', { x: 0.85, y: 1.45, w: 11.5, h: 1.0, fontFace: HEAD, fontSize: 34, color: WHITE, bold: true, margin: 0 });
  const step = (x, n, t, d) => {
    s.addShape(pres.shapes.RECTANGLE, { x, y: 2.95, w: 3.85, h: 2.5, fill: { color: NAVY }, shadow: shadow() });
    s.addShape(pres.shapes.RECTANGLE, { x, y: 2.95, w: 3.85, h: 0.09, fill: { color: GOLD } });
    s.addText(n, { x: x + 0.3, y: 3.2, w: 1, h: 0.7, fontFace: HEAD, fontSize: 30, color: GOLDS, bold: true, margin: 0 });
    s.addText(t, { x: x + 0.3, y: 3.95, w: 3.25, h: 0.45, fontFace: HEAD, fontSize: 15, color: WHITE, bold: true, margin: 0 });
    s.addText(d, { x: x + 0.3, y: 4.4, w: 3.25, h: 0.95, fontFace: BODYF, fontSize: 11.5, color: 'D7DEE6', margin: 0, lineSpacing: 16 });
  };
  step(0.9, '1', 'Discovery & branding', 'Half-day workshop: brand, priority client cohort, integration map.');
  step(4.95, '2', 'Branded pilot', '8–12 weeks: ABN AMRO-themed portal, live with a small client cohort.');
  step(9.0, '3', 'Phased rollout', 'Expand cohorts, deepen integration, scale to the corporate base.');
  s.addText('Aperture  ·  See every flow, every account, every entity.', { x: 0.9, y: 5.95, w: 8, h: 0.35, fontFace: HEAD, fontSize: 14, italic: true, color: GOLDS, margin: 0 });
  s.addText('Confidential — prepared for ABN AMRO  ·  Corporate & Transaction Banking  ·  18 May 2026', { x: 0.9, y: 6.45, w: 11.5, h: 0.3, fontFace: BODYF, fontSize: 10.5, color: 'AEBCC9', margin: 0 });
})();

const OUT = path.join(__dirname, '..', 'Aperture-ABN-AMRO-Cash-Management.pptx');
pres.writeFile({ fileName: OUT }).then(() => console.log('WROTE', OUT, 'slides:', pageNo + 2)).catch(e => { console.error(e); process.exit(1); });

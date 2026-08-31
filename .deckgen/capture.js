// Zero-dependency live screenshot capture via Chrome DevTools Protocol.
// Uses installed Chrome + Node 22 built-ins (global WebSocket, fetch, child_process).
// Drives the already-running app at http://localhost:3000 and writes PNGs to ./assets.
'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHROME = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
].find(p => fs.existsSync(p));
if (!CHROME) { console.error('No Chrome/Edge found'); process.exit(1); }

const APP = 'http://localhost:3000/';
const OUT = path.join(__dirname, 'assets');
fs.mkdirSync(OUT, { recursive: true });
const PORT = 9333;
const VW = 1600, VH = 1000, SCALE = 2;

// Screens to capture: nav label substring -> file name. The clicker matches a
// sidebar <a>/<button> whose visible text starts with `label`.
const SCREENS = [
  { file: 'dashboard',      label: 'Dashboard',            wait: 4200 },
  { file: 'multibank',      label: 'Multi-Bank Liquidity', wait: 3800 },
  { file: 'cash-concentration', label: 'Cash Concentration', wait: 3800 },
  { file: 'notional-pooling',   label: 'Notional Pooling',   wait: 3800 },
  { file: 'in-house-bank',  label: 'In-House Bank',        wait: 3800 },
  { file: 'netting',        label: 'Netting Cycles',       wait: 3800 },
  { file: 'fx-rates',       label: 'FX Rates',             wait: 3500 },
  { file: 'intercompany',   label: 'Intercompany Dashboard', wait: 3800 },
  { file: 'payables',       label: 'Payables (AP)',        wait: 3500 },
  { file: 'transactions',   label: 'All Transactions',     wait: 3500 },
  { file: 'copilot',        label: 'Treasury Copilot',     wait: 3500 },
];

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function getWsUrl() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json`);
      const tabs = await r.json();
      const page = tabs.find(t => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) return page.webSocketDebuggerUrl;
    } catch (_) {}
    await sleep(500);
  }
  throw new Error('CDP endpoint not ready');
}

function cdp(ws) {
  let id = 0; const pending = new Map();
  ws.addEventListener('message', ev => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  });
  return (method, params = {}) => new Promise((resolve, reject) => {
    const myId = ++id;
    pending.set(myId, m => m.error ? reject(new Error(m.error.message)) : resolve(m.result));
    ws.send(JSON.stringify({ id: myId, method, params }));
  });
}

// Runs in-page: click the first nav element whose text starts with `label`.
const CLICKER = `(label => {
  const norm = s => (s||'').replace(/\\s+/g,' ').trim();
  const els = [...document.querySelectorAll('nav a, nav button, aside a, aside button, [class*=sidebar] a, [class*=sidebar] button')];
  const hit = els.find(e => norm(e.innerText).toLowerCase().startsWith(label.toLowerCase()));
  if (hit) { hit.scrollIntoView(); hit.click(); return true; }
  return false;
})`;

// Runs in-page before each shot: kill spinners/animations, scroll top.
const FREEZE = `(() => {
  let s = document.getElementById('__freeze');
  if (!s) { s = document.createElement('style'); s.id='__freeze'; document.head.appendChild(s); }
  s.textContent = '*,*::before,*::after{animation:none !important;transition:none !important;animation-duration:0s !important;scroll-behavior:auto !important}';
  document.querySelectorAll('.animate-spin,.animate-pulse,.animate-ping,.animate-bounce').forEach(e=>e.classList.remove('animate-spin','animate-pulse','animate-ping','animate-bounce'));
  window.scrollTo(0,0);
  return document.fonts ? document.fonts.status : 'na';
})()`;

(async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'cap-'));
  const args = [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--remote-debugging-port=${PORT}`, `--user-data-dir=${tmp}`,
    `--window-size=${VW},${VH}`, '--hide-scrollbars', '--force-color-profile=srgb',
    'about:blank',
  ];
  const chrome = spawn(CHROME, args, { stdio: 'ignore' });
  let exitedBeforeDone = true;
  chrome.on('exit', c => { if (exitedBeforeDone) console.error('Chrome exited early code', c); });

  try {
    const wsUrl = await getWsUrl();
    const ws = new WebSocket(wsUrl);
    await new Promise((res, rej) => { ws.addEventListener('open', res); ws.addEventListener('error', rej); });
    const send = cdp(ws);

    await send('Page.enable');
    await send('Runtime.enable');
    await send('Emulation.setDeviceMetricsOverride', { width: VW, height: VH, deviceScaleFactor: SCALE, mobile: false });
    await send('Page.navigate', { url: APP });
    await sleep(6000); // initial app boot + first data load

    const results = [];
    for (const sc of SCREENS) {
      try {
        if (sc.label !== 'Dashboard') {
          const r = await send('Runtime.evaluate', { expression: `${CLICKER}(${JSON.stringify(sc.label)})`, returnByValue: true });
          if (!r.result || r.result.value !== true) { results.push(`${sc.file}: NAV-MISS`); continue; }
        }
        await sleep(sc.wait);
        await send('Runtime.evaluate', { expression: FREEZE });
        await sleep(450);
        const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
        fs.writeFileSync(path.join(OUT, sc.file + '.png'), Buffer.from(shot.data, 'base64'));
        const sz = fs.statSync(path.join(OUT, sc.file + '.png')).size;
        results.push(`${sc.file}: OK ${(sz/1024).toFixed(0)}KB`);
      } catch (e) {
        results.push(`${sc.file}: ERR ${e.message}`);
      }
    }
    console.log(results.join('\n'));
    exitedBeforeDone = false;
    ws.close();
  } finally {
    try { chrome.kill(); } catch (_) {}
  }
})().catch(e => { console.error('FATAL', e); process.exit(1); });

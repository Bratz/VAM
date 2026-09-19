// Renders every story from a built Storybook (storybook-static) in Chromium, in light and dark,
// and checks that it renders without a Storybook error and that text passes axe's color-contrast rule.
//
//   npm run build-storybook && npm run storybook:check
//
// Render failures always fail the run. Contrast violations are reported and only fail the run when
// STORYBOOK_A11Y_STRICT=1 (or --strict), so the check can be tightened once the baseline is clean.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { chromium } from 'playwright';

const require = createRequire(import.meta.url);
const root = path.resolve(process.argv.find((a) => a.startsWith('--dir='))?.slice(6) ?? 'storybook-static');
const strict = process.env.STORYBOOK_A11Y_STRICT === '1' || process.argv.includes('--strict');
const axeSource = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.woff2': 'font/woff2', '.woff': 'font/woff', '.png': 'image/png', '.ico': 'image/x-icon', '.map': 'application/json' };
const server = http.createServer((req, res) => {
  const rel = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  const file = path.join(root, rel === '/' ? 'index.html' : rel);
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'content-type': MIME[path.extname(file)] ?? 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});
await new Promise((r) => server.listen(0, r));
const base = `http://localhost:${server.address().port}`;

const index = JSON.parse(fs.readFileSync(path.join(root, 'index.json'), 'utf8'));
const stories = Object.values(index.entries).filter((e) => e.type === 'story');
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1200, height: 900 } });

const renderFailures = [];
const contrast = [];
for (const s of stories) {
  await page.goto(`${base}/iframe.html?id=${s.id}&viewMode=story`, { waitUntil: 'load' });
  await page.waitForFunction(() => document.body.classList.contains('sb-show-main') || document.body.classList.contains('sb-show-errordisplay'), null, { timeout: 15000 }).catch(() => {});
  const failed = await page.evaluate(() => document.body.classList.contains('sb-show-errordisplay') || !document.body.classList.contains('sb-show-main'));
  if (failed) { renderFailures.push(s.id); continue; }
  await page.addScriptTag({ content: axeSource });
  for (const theme of ['light', 'dark']) {
    await page.evaluate((t) => { document.documentElement.classList.remove('light', 'dark'); document.documentElement.classList.add(t); }, theme);
    await page.waitForTimeout(150);
    // The a11y addon also runs axe on story load; wait out "Axe is already running" and retry.
    let result;
    for (let attempt = 0; attempt < 20 && !result; attempt++) {
      result = await page.evaluate(async () => {
        try {
          const r = await window.axe.run('#storybook-root', { runOnly: { type: 'rule', values: ['color-contrast'] } });
          return r.violations.flatMap((v) => v.nodes.map((n) => ({ target: n.target.join(' '), summary: n.any[0]?.message ?? v.help })));
        } catch (e) {
          return String(e).includes('already running') ? null : Promise.reject(e);
        }
      });
      if (!result) await page.waitForTimeout(300);
    }
    if (!result) { renderFailures.push(s.id + ' (axe never became available)'); continue; }
    for (const v of result) contrast.push({ id: s.id, theme, ...v });
  }
}
await browser.close();
server.close();

console.log(`stories checked: ${stories.length}`);
console.log(`render failures: ${renderFailures.length}${renderFailures.length ? '\n  ' + renderFailures.join('\n  ') : ''}`);
console.log(`contrast violations: ${contrast.length}${strict ? ' (strict)' : ' (report-only; set STORYBOOK_A11Y_STRICT=1 to fail)'}`);
for (const c of contrast.slice(0, 40)) console.log(`  ${c.id} [${c.theme}] ${c.target} — ${c.summary}`);
process.exit(renderFailures.length || (strict && contrast.length) ? 1 : 0);

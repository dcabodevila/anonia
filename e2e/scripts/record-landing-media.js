// Records the landing page demo clips from a running Anonimuse server.
//
// Usage (from e2e/):
//   node scripts/record-landing-media.js <baseUrl> <sample.pdf>
//   python scripts/frames-to-gif.py .runtime/landing-frames ../src/main/resources/web/landing
//
// Web assets are served from the working tree so the clips match the current source,
// even when target/doc-anonymizer.jar is older than src/main/resources/web.
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('@playwright/test');

const root = path.resolve(__dirname, '..', '..');
const webRoot = path.join(root, 'src', 'main', 'resources', 'web');
const framesRoot = path.join(root, 'e2e', '.runtime', 'landing-frames');
const viewport = { width: 1280, height: 760 };
const frameInterval = 110;

const contentTypes = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript',
  '.png': 'image/png', '.gif': 'image/gif' };

const cursorScript = () => {
  window.addEventListener('DOMContentLoaded', () => {
    const cursor = document.createElement('div');
    cursor.id = 'demo-cursor';
    cursor.innerHTML = '<svg width="26" height="30" viewBox="0 0 26 30"><path d="M2 2l20 12-9 2-4 9z" '
      + 'fill="#fff" stroke="#1a1218" stroke-width="2" stroke-linejoin="round"/></svg>';
    Object.assign(cursor.style, { position: 'fixed', left: '-40px', top: '-40px', zIndex: 99999,
      pointerEvents: 'none', transition: 'transform .12s', filter: 'drop-shadow(0 2px 3px rgba(0,0,0,.5))' });
    document.body.appendChild(cursor);
    document.addEventListener('mousemove', e => {
      cursor.style.left = e.clientX - 2 + 'px';
      cursor.style.top = e.clientY - 2 + 'px';
    }, true);
    document.addEventListener('mousedown', () => { cursor.style.transform = 'scale(.8)'; }, true);
    document.addEventListener('mouseup', () => { cursor.style.transform = 'scale(1)'; }, true);
  });
};

class Recorder {
  constructor(page) { this.page = page; this.running = false; }

  async start(clip) {
    this.dir = path.join(framesRoot, clip);
    await fs.rm(this.dir, { recursive: true, force: true });
    await fs.mkdir(this.dir, { recursive: true });
    this.index = 0;
    this.running = true;
    this.loop = (async () => {
      while (this.running) {
        const started = Date.now();
        const name = String(this.index++).padStart(4, '0') + '.png';
        await this.page.screenshot({ path: path.join(this.dir, name) }).catch(() => {});
        await new Promise(resolve => setTimeout(resolve, Math.max(0, frameInterval - (Date.now() - started))));
      }
    })();
  }

  async stop() {
    this.running = false;
    await this.loop;
    this.loop = null;
  }
}

const pointer = { x: 640, y: 600 };

// Glides the pointer so the recorded frames show the cursor travelling to its target.
async function glide(page, x, y, steps = 16) {
  const from = { ...pointer };
  for (let step = 1; step <= steps; step++) {
    const t = step / steps;
    const eased = t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
    await page.mouse.move(from.x + (x - from.x) * eased, from.y + (y - from.y) * eased);
    await page.waitForTimeout(35);
  }
  pointer.x = x;
  pointer.y = y;
}

async function moveTo(page, locator, steps = 16) {
  const box = await locator.boundingBox();
  await glide(page, box.x + box.width / 2, box.y + box.height / 2, steps);
}

async function clickOn(page, locator) {
  await locator.scrollIntoViewIfNeeded();
  await moveTo(page, locator);
  await page.waitForTimeout(250);
  await locator.click();
}

const pause = (page, ms) => page.waitForTimeout(ms);

async function recordDetect(page, recorder, samplePdf) {
  await page.mouse.move(640, 600);
  await recorder.start('detect');
  await pause(page, 900);
  const browse = page.locator('#browse');
  await moveTo(page, browse, 25);
  await pause(page, 300);
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), browse.click()]);
  await chooser.setFiles(samplePdf);
  await page.locator('#workspace:not(.hidden)').waitFor();
  await pause(page, 1200);
  await moveTo(page, page.locator('#doctext .hl').first(), 20);
  await pause(page, 600);
  await page.mouse.wheel(0, 160);
  await pause(page, 1600);
  await recorder.stop();
}

async function recordReview(page, recorder) {
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.locator('#doctext').evaluate(node => { node.scrollTop = 0; });
  await recorder.start('review');
  await pause(page, 700);
  const postcode = page.locator('.entity', { hasText: '28013' }).locator('input[type="checkbox"]');
  await clickOn(page, postcode);
  await pause(page, 1100);
  const search = page.locator('#entity-search');
  await clickOn(page, search);
  await search.pressSequentially('Juan', { delay: 110 });
  await pause(page, 700);
  await clickOn(page, page.locator('.entity', { hasText: 'Juan Perez Lopez' }).locator('.entity-locate'));
  await pause(page, 1500);
  await search.fill('');
  await search.dispatchEvent('input');
  await pause(page, 500);
  await recorder.stop();
}

async function recordCompare(page, recorder) {
  await page.evaluate(() => window.scrollTo(0, 0));
  await recorder.start('compare');
  await pause(page, 600);
  await clickOn(page, page.locator('#apply'));
  await page.locator('#result-status.success').waitFor();
  await pause(page, 1300);
  await clickOn(page, page.locator('#compare'));
  await pause(page, 1500);
  await glide(page, 640, 500);
  await page.mouse.wheel(0, 220);
  await pause(page, 1800);
  await recorder.stop();
}

async function recordExport(page, recorder) {
  await page.locator('#compare').click();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.locator('#tab-result-control').click();
  await pause(page, 300);
  await recorder.start('export');
  await pause(page, 800);
  await clickOn(page, page.locator('#copy'));
  await pause(page, 1300);
  const download = page.waitForEvent('download');
  await clickOn(page, page.locator('#download'));
  await download;
  await pause(page, 1500);
  await recorder.stop();
}

async function main() {
  const [baseUrl, samplePdf] = process.argv.slice(2);
  if (!baseUrl || !samplePdf) throw new Error('Usage: record-landing-media.js <baseUrl> <sample.pdf>');
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport, acceptDownloads: true, locale: 'es-ES' });
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: baseUrl });
  const page = await context.newPage();
  await page.addInitScript(cursorScript);
  await page.route(url => !url.pathname.startsWith('/api/') && url.pathname !== '/health', async route => {
    const pathname = new URL(route.request().url()).pathname;
    const file = path.join(webRoot, pathname === '/' ? 'index.html' : pathname.slice(1));
    try {
      await route.fulfill({ body: await fs.readFile(file),
        contentType: contentTypes[path.extname(file)] || 'application/octet-stream' });
    } catch (_) {
      await route.fulfill({ status: 404, body: 'No encontrado.' });
    }
  });
  await page.goto(baseUrl);
  await page.evaluate(() => document.fonts.ready);

  const recorder = new Recorder(page);
  try {
    await recordDetect(page, recorder, path.resolve(samplePdf));
    await recordReview(page, recorder);
    await recordCompare(page, recorder);
    await recordExport(page, recorder);
  } finally {
    await recorder.stop();
    await browser.close();
  }
  console.log(`Frames written to ${framesRoot}`);
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

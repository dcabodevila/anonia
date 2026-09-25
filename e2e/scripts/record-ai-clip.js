// Records the "paste into your AI" landing clip: the sample PDF is anonymized in
// Anonimuse and the resulting Markdown is pasted into a logged-out ChatGPT window.
//
// Usage (from e2e/, with Anonimuse running):
//   node scripts/record-ai-clip.js <baseUrl> <sample.pdf>
// ChatGPT blocks headless browsers, so this opens a visible Chromium window.
// Only the synthetic, already anonymized sample Markdown is sent to chatgpt.com.
const path = require('node:path');
const { chromium } = require('@playwright/test');
const { Recorder, cursorScript, glide, moveTo, pause, viewport } = require('./record-landing-media');

const prompt = 'Resume este acta en tres puntos:';

async function anonymizedMarkdown(browser, baseUrl, samplePdf) {
  const page = await browser.newPage();
  await page.goto(baseUrl);
  await page.setInputFiles('#file', samplePdf);
  await page.locator('#workspace:not(.hidden)').waitFor();
  await page.click('#apply');
  await page.locator('#result-status.success').waitFor();
  const markdown = await page.locator('#markdown').textContent();
  await page.close();
  return markdown.trim();
}

async function main() {
  const [baseUrl, samplePdf] = process.argv.slice(2);
  if (!baseUrl || !samplePdf) throw new Error('Usage: record-ai-clip.js <baseUrl> <sample.pdf>');
  const browser = await chromium.launch({ headless: false });
  const markdown = await anonymizedMarkdown(browser, baseUrl, path.resolve(samplePdf));
  const context = await browser.newContext({ viewport, locale: 'es-ES' });
  const page = await context.newPage();
  await page.addInitScript(cursorScript);
  await page.goto('https://chatgpt.com/', { waitUntil: 'domcontentloaded' });
  const composer = page.getByPlaceholder(/ChatGPT/).or(page.locator('#prompt-textarea')).first();
  await composer.waitFor({ timeout: 30_000 });
  const rejectCookies = page.getByRole('button', { name: /Rechazar/ });
  if (await rejectCookies.count()) await rejectCookies.first().click();
  await pause(page, 800);

  const recorder = new Recorder(page);
  try {
    await recorder.start('ai');
    await pause(page, 700);
    await moveTo(page, composer, 22);
    await composer.click();
    await composer.pressSequentially(prompt, { delay: 45 });
    await page.keyboard.press('Shift+Enter');
    await pause(page, 400);
    await page.keyboard.insertText(markdown);
    await pause(page, 1600);
    const send = page.locator('[data-testid="send-button"], #composer-submit-button, '
      + 'button[aria-label*="Enviar" i], button[aria-label*="send" i]').first();
    if (await send.count()) {
      await moveTo(page, send, 18);
      await send.click();
    } else {
      await page.keyboard.press('Enter');
    }
    // The logged-out UI changes often: wait for an answer when it is detectable, otherwise a fixed time.
    await page.locator('[data-message-author-role="assistant"]').first()
      .waitFor({ timeout: 20_000 }).catch(() => {});
    await pause(page, 9000);
    await glide(page, 640, 420);
    await pause(page, 1500);
  } finally {
    await recorder.stop();
    await browser.close();
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

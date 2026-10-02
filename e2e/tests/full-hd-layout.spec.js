const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');
const runtime = path.resolve(__dirname, '../.runtime');

async function inspect(page, testInfo, viewport, mode, selectors) {
  await page.evaluate(() => scrollTo({ top:0, behavior:'instant' }));
  const geometry = await page.evaluate(selectors => {
    const surfaces = selectors.map(selector => {
      const element = document.querySelector(selector);
      const r = element.getBoundingClientRect();
      return { selector, top:r.top, bottom:r.bottom, height:r.height,
        clientHeight:element.clientHeight, scrollHeight:element.scrollHeight };
    });
    return { surfaces, width:document.documentElement.scrollWidth, viewport:innerWidth };
  }, selectors);
  console.log(JSON.stringify({ viewport, mode, geometry }));
  await page.screenshot({ path:testInfo.outputPath(`${mode}.png`), fullPage:true });
  expect.soft(geometry.width).toBeLessThanOrEqual(viewport.width);
  for (const surface of geometry.surfaces) {
    expect.soft(surface.height, `${mode} ${surface.selector} reading space`).toBeGreaterThan(100);
    if (viewport.width > 1100) {
      expect.soft(surface.top, `${mode} ${surface.selector} top`).toBeGreaterThanOrEqual(0);
      if ((mode === 'document' && surface.selector === '#doctext') || mode === 'result') {
        expect.soft(surface.top, `${mode} reading starts within compact chrome`).toBeLessThanOrEqual(170);
      }
      if (mode === 'comparison') {
        expect.soft(surface.top, 'comparison compact labels').toBeLessThanOrEqual(200);
        expect.soft(surface.height, 'comparison reading budget').toBeGreaterThanOrEqual(viewport.height - 310);
      }
      expect.soft(surface.bottom, `${mode} ${surface.selector} bottom`).toBeLessThanOrEqual(viewport.height - 20);
    }
    const locator = page.locator(surface.selector);
    await locator.scrollIntoViewIfNeeded();
    await expect.soft(locator).toBeInViewport();
    // Verify that the final content is reachable through its own scroll region.
    await locator.evaluate(element => { element.scrollTop = element.scrollHeight; });
    const remaining = await locator.evaluate(element => element.scrollHeight - element.clientHeight - element.scrollTop);
    expect.soft(remaining).toBeLessThanOrEqual(1);
  }
}

for (const viewport of [{ width:1920, height:1080 }, { width:1920, height:900 }, { width:1280, height:760 }, { width:390, height:844 }]) {
  test(`workspace reading surfaces remain reachable at ${viewport.width}x${viewport.height}`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    const { baseUrl } = JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8'));
    await page.goto(baseUrl);
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures/seeded.pdf'));
    await expect(page.locator('#workspace')).toBeVisible();
    await expect.soft(page.locator('.topbar #document-chip')).toBeVisible();
    await expect.soft(page.locator('#document-chip')).toContainText('seeded.pdf');
    await expect.soft(page.locator('#dropzone')).toBeHidden();
    await expect(page.locator('#result-status[aria-live="polite"]')).toContainText('Resultado pendiente');
    await inspect(page, testInfo, viewport, 'document', ['#doctext', '#entities']);
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    await inspect(page, testInfo, viewport, 'result', ['#markdown']);
    const notice = page.locator('.disclaimer');
    const checkNotice = async () => {
      expect.soft(await notice.evaluate(element => element.getBoundingClientRect().height)).toBeLessThanOrEqual(32);
      await expect.soft(notice).toContainText('Las etiquetas mantienen distinguibles a las personas y fechas, importes, cargos y hechos singulares permanecen intactos.');
      await expect.soft(notice.locator('summary')).toBeVisible();
      if (await notice.locator('summary').count()) {
        await notice.locator('summary').click();
        await expect.soft(notice.locator('p')).toBeVisible();
        await notice.locator('summary').click();
      }
    };
    await checkNotice();
    await page.locator('#compare').click();
    await expect.soft(page.locator('.main-tabbar #download')).toBeVisible();
    await expect.soft(page.locator('.main-tabbar #copy')).toBeVisible();
    await checkNotice();
    await inspect(page, testInfo, viewport, 'comparison', ['#doctext', '#markdown']);
    if (viewport.width > 850) {
      const rects = await page.locator('#doctext, #markdown').evaluateAll(elements => elements.map(element => {
        const rect = element.getBoundingClientRect();
        return { top:rect.top, height:rect.height };
      }));
      expect(Math.abs(rects[0].top - rects[1].top)).toBeLessThanOrEqual(1);
      if (viewport.width > 1100) expect(Math.abs(rects[0].height - rects[1].height)).toBeLessThanOrEqual(1);
    }
    await page.locator('#compare').click();
    await page.locator('#tab-document-control').click();
    await expect(page.locator('#entity-controls')).toBeVisible();
    await expect(page.locator('#apply')).toBeEnabled();
    const picker = page.waitForEvent('filechooser');
    await page.locator('#change-document').click();
    expect((await picker).element()).toBeTruthy();
    await page.locator('#file').setInputFiles({ name:'replacement.pdf', mimeType:'application/pdf', buffer:await fs.readFile(path.join(runtime, 'fixtures/seeded.pdf')) });
    await expect(page.locator('#document-chip')).toContainText('replacement.pdf');
    await expect(page.locator('#result-status')).toContainText('Resultado pendiente');
    // Dropping outside the hidden landing dropzone must also replace the file.
    const transfer = await page.evaluateHandle(async base64 => {
      const bytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
      const data = new DataTransfer();
      data.items.add(new File([bytes], 'dropped.pdf', { type:'application/pdf' }));
      return data;
    }, (await fs.readFile(path.join(runtime, 'fixtures/seeded.pdf'))).toString('base64'));
    await page.locator('#doctext').dispatchEvent('drop', { dataTransfer:transfer });
    await expect(page.locator('#document-chip')).toContainText('dropped.pdf');
    await transfer.dispose();
  });
}

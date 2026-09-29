const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

test('comparison projection agrees with real server labels and retained source offsets', async ({ page }) => {
  const runtime = path.resolve(__dirname, '../.runtime');
  const { baseUrl } = JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8'));
  await page.setViewportSize({ width:1920, height:1080 });
  await page.goto(baseUrl);
  await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures/seeded.pdf'));
  await expect(page.locator('#workspace')).toBeVisible();
  await page.locator('#apply').click();
  await expect(page.locator('#result-status')).toContainText('Anonimización completada');
  await page.locator('#compare').click();
  const result = page.locator('#markdown .result-entity').first();
  await expect(result).toBeVisible();
  const id = await result.getAttribute('data-comparison-occurrence');
  const source = page.locator(`#doctext [data-comparison-occurrence="${id}"]`).first();
  expect(await source.evaluate(element => getComputedStyle(element).color))
    .toBe(await result.evaluate(element => getComputedStyle(element).color));
  await source.hover();
  await expect(result).toHaveClass(/comparison-match/);
  await page.locator('#compare').click();
  await expect(page.locator('#markdown .comparison-match')).toHaveCount(0);
});

test('comparison links exact replaced and preserved occurrences without interpreting HTML', async ({ page }, testInfo) => {
  const { baseUrl } = JSON.parse(await fs.readFile(path.resolve(__dirname, '../.runtime/server.json'), 'utf8'));
  const text = '[P] Ana\r\nRuiz | Ana\r\nRuiz | <img src=x onerror=alert(1)>';
  const detections = [
    { id:'a', type:'PERSONA', start:4, end:13, entityKey:'person' },
    { id:'b', type:'PERSONA', start:16, end:25, entityKey:'person' },
    { id:'c', type:'EMAIL', start:28, end:text.length, entityKey:'kept' }
  ].map(d => ({ ...d, provenance:'REGEX', confidence:1 }));
  await page.route('**/api/analyze', route => route.fulfill({ json:{
    jobId:'comparison', text, pageCount:1, elapsedMs:1, types:['PERSONA', 'EMAIL'], detections
  } }));
  await page.route('**/api/apply', route => route.fulfill({ json:{
    deliverable:true, markdown:'[P] [P]\r\n | [P]\r\n | <img src=x onerror=alert(1)>',
    substitutions:2, entities:1, labels:[{ entityKey:'person', label:'[P]' }], findings:[]
  } }));
  await page.setViewportSize({ width:1920, height:1080 });
  await page.goto(baseUrl);
  await page.locator('#file').setInputFiles({ name:'comparison.pdf', mimeType:'application/pdf', buffer:Buffer.from('fixture') });
  await expect(page.locator('#workspace')).toBeVisible();
  await page.locator('[data-entity-key="kept"] input[type=checkbox]').uncheck();
  await page.locator('#apply').click();
  await expect(page.locator('#result-status')).toContainText('Anonimización completada');
  await page.locator('#compare').click();
  await expect(page.locator('#markdown .result-entity')).toHaveCount(3);
  await expect(page.locator('#markdown img')).toHaveCount(0);
  for (const id of ['a', 'b', 'c']) {
    const source = page.locator(`#doctext [data-comparison-occurrence="${id}"]`);
    const result = page.locator(`#markdown [data-comparison-occurrence="${id}"]`);
    expect(await source.evaluate(element => getComputedStyle(element).color))
      .toBe(await result.evaluate(element => getComputedStyle(element).color));
    await source.hover();
    await expect(result).toHaveClass(/comparison-match/);
    await expect(page.locator('#markdown .comparison-match')).toHaveCount(1);
    await page.locator('#compare').hover();
    await expect(page.locator('#markdown .comparison-match')).toHaveCount(0);
    await source.focus();
    await expect(result).toHaveClass(/comparison-match/);
    await source.evaluate(element => element.blur());
    await expect(page.locator('#markdown .comparison-match')).toHaveCount(0);
  }
  await page.locator('#doctext [data-comparison-occurrence="b"]').hover();
  const inspection = await page.locator('#markdown .comparison-match').evaluate(element => {
    const style = getComputedStyle(element);
    return { color:style.color, outline:style.outlineWidth, underline:style.textDecorationLine };
  });
  console.log(JSON.stringify({ engine:'chromium', viewport:'1920x1080', inspection }));
  expect(inspection.outline).toBe('2px');
  expect(inspection.underline).toContain('underline');
  await page.screenshot({ path:testInfo.outputPath('comparison-hover.png') });
  await page.locator('#compare').click();
  await page.locator('#tab-document-control').click();
  await page.locator('[data-entity-key="kept"] input[type=checkbox]').check();
  await expect(page.locator('#markdown .result-entity')).toHaveCount(0);
  await expect(page.locator('#markdown .comparison-match')).toHaveCount(0);
});

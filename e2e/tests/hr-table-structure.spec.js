const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

// The generated hr-300.xlsx (scripts/fixtures.js, e2e/perf/HrWorkbookGenerator.java) goes through the
// real upload -> review -> "Anonimizar" flow against the packaged server.
const runtime = path.resolve(__dirname, '../.runtime');
const workbook = path.join(runtime, 'hr-fixtures', 'hr-300.xlsx');

// Playwright cannot read a response body after the page replaces its views, so the page keeps a
// copy of each API body (the same technique as tests-perf/hr-excel-performance.spec.js).
function captureApi() {
  window.__api = {};
  const original = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const response = await original(input, init);
    const url = String(input && input.url || input);
    const name = url.includes('/api/analyze') ? 'analyze' : url.includes('/api/apply') ? 'apply' : null;
    if (name) window.__api[name] = await response.clone().text();
    return response;
  };
}

async function captured(page, name) {
  await page.waitForFunction(key => window.__api[key], name, { timeout: 120_000, polling: 100 });
  return JSON.parse(await page.evaluate(key => window.__api[key], name));
}

async function openWorkbook(page) {
  const { baseUrl } = JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8'));
  await page.addInitScript(captureApi);
  await page.goto(baseUrl);
  await expect(page.locator('#dropzone')).toBeVisible();
  await page.locator('#file').setInputFiles(workbook);
  const analysis = await captured(page, 'analyze');
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#error')).toBeHidden();
  await expect(page.locator('#entities [data-entity-key]').first()).toBeVisible();
  return analysis;
}

async function apply(page) {
  const started = Date.now();
  await page.locator('#apply').click();
  const data = await captured(page, 'apply');
  await expect(page.locator('#result-status')).toContainText('Anonimización completada', { timeout: 120_000 });
  return { data, ms: Date.now() - started };
}

const stripMetadata = markdown => markdown.replace(/^<!--[\s\S]*?-->\r?\n(?:[ \t]*\r?\n)*/, '');
const tabsPerLine = text => text.split(/\r?\n/).map(line => line.split('\t').length - 1);

test('S5 applying with most people, all CP and all CODIGO rejected completes without hanging', async ({ page }) => {
  test.setTimeout(180_000);
  await openWorkbook(page);
  const bulk = page.locator('#bulk-uncheck');
  const filter = page.locator('#entity-type-filter');

  await filter.selectOption('PERSONA');
  await expect(bulk).toHaveText('Desmarcar todos');
  await bulk.click();
  await expect(bulk).toHaveText('Marcar todos');
  // Keep a few people: re-check the first five listed rows.
  const boxes = page.locator('#entities [data-entity-key] input[type=checkbox]');
  expect(await boxes.count()).toBeGreaterThan(50);
  for (let i = 0; i < 5; i++) await boxes.nth(i).check();
  await expect(boxes.nth(0)).toBeChecked();
  await expect(boxes.nth(10)).not.toBeChecked();

  for (const type of ['CP', 'CODIGO']) {
    await filter.selectOption(type);
    await expect(bulk).toBeEnabled();
    await expect(bulk).toHaveText('Desmarcar todos');
    await bulk.click();
    await expect(bulk).toHaveText('Marcar todos');
  }

  const { data, ms } = await apply(page);
  expect(ms, `apply took ${ms} ms`).toBeLessThan(30_000);
  await expect(page.locator('#apply-loading')).toBeHidden();
  expect(typeof data.markdown).toBe('string');
  expect(data.markdown).not.toMatch(/\[CP_\d+\]/);
  expect(data.markdown).not.toMatch(/\[CODIGO_\d+\]/);
});

test('S6 anonymized HR workbook keeps its table structure and header words', async ({ page }) => {
  test.setTimeout(180_000);
  const analysis = await openWorkbook(page);
  const { data } = await apply(page);
  const markdown = stripMetadata(data.markdown);

  // Every row keeps the TAB-separated column count of the source.
  const source = tabsPerLine(analysis.text);
  const output = tabsPerLine(markdown);
  expect(output.length).toBe(source.length);
  const broken = source.map((tabs, line) => [line, tabs, output[line]]).filter(([, a, b]) => a !== b);
  expect(broken.slice(0, 5), 'rows whose TAB count changed [line, source, output]').toEqual([]);
  expect(source.some(tabs => tabs >= 5)).toBe(true);

  // No label glued to the rest of a word ("Ana" inside "Analista").
  expect(markdown).not.toMatch(/\[PERSONA_\d+\]\p{L}/u);
  expect(markdown).toContain('Analista');

  // An IBAN never swallows the next cell.
  expect(markdown).not.toMatch(/\[IBAN_\d+\]\/2026/);
  expect(markdown).toMatch(/\[IBAN_\d+\]\t\d{2}\/2026/);

  // Header cells and job titles are not people.
  expect(markdown).toContain('ID Empleado');
  for (const word of ['Empleado', 'Técnico', 'Técnico Senior']) {
    expect(markdown, `${word} as a whole cell`).toMatch(new RegExp(`(^|\\t)${word}(\\t|$)`, 'm'));
  }

  // Real names are still anonymized.
  expect(markdown).toContain('[PERSONA_');
  const people = [...new Set(analysis.detections.filter(d => d.type === 'PERSONA')
    .map(d => analysis.text.slice(d.start, d.end).trim()).filter(name => /\s/.test(name)))].slice(0, 30);
  expect(people.length).toBeGreaterThan(5);
  for (const name of people) expect(markdown, `name ${name} leaked`).not.toContain(name);
});

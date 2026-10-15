const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

const root = path.resolve(__dirname, '..', '..');
const runtime = path.join(root, 'e2e', '.runtime');
const fixtures = path.join(runtime, 'fixtures');

// Every fixture carries the same synthetic text (see scripts/fixtures.js), so one oracle applies.
// "Titular" sits next to a name (same line, or the neighbouring table cell): it must survive.
const DOCUMENT = ['Acta de prueba sintética', 'Titular'];
const WORKBOOK = ['## Comparecientes', 'Descripción', 'Importe', 'Titular'];
const FORMATS = [
  { label: 'Markdown', file: 'seeded.md', kept: DOCUMENT },
  { label: 'plain text', file: 'seeded.txt', kept: DOCUMENT },
  { label: 'Word .docx', file: 'seeded.docx', kept: DOCUMENT },
  { label: 'legacy Word .doc', file: 'seeded.doc', kept: DOCUMENT },
  { label: 'Excel .xlsx', file: 'seeded.xlsx', kept: WORKBOOK },
  { label: 'legacy Excel .xls', file: 'seeded.xls', kept: WORKBOOK }
];

async function open(page) {
  const { baseUrl } = JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8'));
  await page.goto(baseUrl);
  await expect(page.locator('#dropzone')).toBeVisible();
}

async function anonymizeAndDownload(page, name) {
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#error')).toBeHidden();
  await expect(page.locator('.entity').filter({ hasText: '12345678Z' }).first()).toBeVisible();

  await page.locator('#apply').click();
  await expect(page.locator('#result-status')).toContainText('Anonimización completada');
  await expect(page.locator('#download')).toBeEnabled();

  const download = page.waitForEvent('download');
  await page.locator('#download').click();
  const output = path.join(runtime, name);
  await (await download).saveAs(output);
  return fs.readFile(output, 'utf8');
}

test.describe('document formats beyond PDF and images, against the local Java server', () => {
  test.describe.configure({ mode: 'serial' });

  test('the served picker offers every registered format', async ({ page }) => {
    await open(page);
    const accept = (await page.locator('#file').getAttribute('accept')).split(',');
    for (const extension of ['.pdf', '.jpg', '.png', '.docx', '.doc', '.xlsx', '.xls', '.md', '.txt']) {
      expect(accept).toContain(extension);
    }
    expect(accept.join(',')).not.toContain('{{');
  });

  for (const format of FORMATS) {
    test(`anonymizes and downloads a ${format.label} document`, async ({ page }) => {
      await open(page);
      await page.locator('#file').setInputFiles(path.join(fixtures, format.file));
      await expect(page.locator('#drop-title')).toContainText(format.file);

      const markdown = await anonymizeAndDownload(page, `${format.file}.download.md`);

      expect(markdown).not.toContain('12345678Z');
      expect(markdown).not.toContain('Juan García Pérez');
      expect(markdown).toContain('[PERSONA_');
      for (const text of format.kept) expect(markdown).toContain(text);
    });
  }

  test('recognises a Word document by its content despite a misleading name', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles({
      name: 'adjunto.bin',
      mimeType: 'application/octet-stream',
      buffer: await fs.readFile(path.join(fixtures, 'seeded.docx'))
    });

    const markdown = await anonymizeAndDownload(page, 'renamed-word.download.md');

    expect(markdown).not.toContain('12345678Z');
    expect(markdown).toContain('Acta de prueba sintética');
  });
});

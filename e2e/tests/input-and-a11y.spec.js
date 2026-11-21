const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

// Input and accessibility journeys (odd/tasks/e2e-session-features.md T5, G8-G11) against the
// real Java server. Nothing is mocked: drops use a real DataTransfer built in the page.
const runtime = path.resolve(__dirname, '../.runtime');

async function baseUrl() {
  return JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8')).baseUrl;
}

async function open(page) {
  await page.goto(await baseUrl());
  await expect(page.locator('#dropzone')).toBeVisible();
}

const SEEDED_TEXT = [
  'Acta de prueba sintética',
  'Comparece Juan García Pérez con DNI 12345678Z.',
  'Titular: María López Ruiz', ''
].join('\n');

// Dispatches the drag sequence on the landing dropzone with a DataTransfer built in the page.
async function dragSequence(page, payload, events) {
  return page.evaluate(({ payload, events }) => {
    const transfer = new DataTransfer();
    if (payload.file) transfer.items.add(new File([payload.file.text], payload.file.name, { type: 'text/plain' }));
    else transfer.setData('text/plain', payload.text);
    const zone = document.getElementById('dropzone');
    return events.map(type => {
      const event = new DragEvent(type, { bubbles: true, cancelable: true, dataTransfer: transfer });
      zone.dispatchEvent(event);
      return { type, defaultPrevented: event.defaultPrevented, dragging: zone.classList.contains('dragging') };
    });
  }, { payload, events });
}

async function saveDownload(page, name) {
  const download = page.waitForEvent('download');
  await page.locator('#download').click();
  const saved = await download;
  const output = path.join(runtime, name);
  await saved.saveAs(output);
  return { suggested: saved.suggestedFilename(), body: await fs.readFile(output, 'utf8') };
}

test.describe('G8 dropping a file on the landing dropzone', () => {
  test('a real file drop highlights the zone, then analyzes the file', async ({ page }) => {
    await open(page);
    const file = { file: { name: 'arrastrado.txt', text: SEEDED_TEXT } };
    const steps = await dragSequence(page, file, ['dragenter', 'dragover']);
    expect(steps).toEqual([
      { type: 'dragenter', defaultPrevented: true, dragging: true },
      { type: 'dragover', defaultPrevented: true, dragging: true }
    ]);
    await expect(page.locator('#dropzone')).toHaveClass(/dragging/);
    const [drop] = await dragSequence(page, file, ['drop']);
    expect(drop).toEqual({ type: 'drop', defaultPrevented: true, dragging: false });
    await expect(page.locator('#workspace')).toBeVisible();
    await expect(page.locator('#drop-title')).toContainText('arrastrado.txt');
    await expect(page.locator('#doctext')).toContainText('Juan García Pérez');
    await expect(page.locator('#entities [data-entity-key]').first()).toBeVisible();
    await expect(page.locator('#error')).toBeHidden();
  });

  test('dragging a non-file item does nothing', async ({ page }) => {
    await open(page);
    const steps = await dragSequence(page, { text: 'texto arrastrado' }, ['dragenter', 'dragover', 'drop']);
    expect(steps).toEqual([
      { type: 'dragenter', defaultPrevented: false, dragging: false },
      { type: 'dragover', defaultPrevented: false, dragging: false },
      { type: 'drop', defaultPrevented: false, dragging: false }
    ]);
    await expect(page.locator('#loading')).toBeHidden();
    await expect(page.locator('#workspace')).toBeHidden();
    await expect(page.locator('#error')).toBeHidden();
    await expect(page.locator('#dropzone')).not.toHaveClass(/dragging/);
  });
});

test.describe('G9 non-ASCII file names', () => {
  test('a .txt with accents, eñe and parentheses analyzes and names its download from the stem', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles({
      name: 'Contrato ñandú (1).txt', mimeType: 'text/plain', buffer: Buffer.from(SEEDED_TEXT, 'utf8')
    });
    await expect(page.locator('#workspace')).toBeVisible();
    await expect(page.locator('#drop-title')).toContainText('Contrato ñandú (1).txt');
    await expect(page.locator('#doctext')).toContainText('Juan García Pérez');
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    const saved = await saveDownload(page, 'nonascii-txt.download.md');
    expect(saved.suggested).toBe('Contrato ñandú (1)-anonimused.md');
    expect(saved.body).toContain('[PERSONA_');
    expect(saved.body).not.toContain('12345678Z');
  });

  test('a .docx with a non-ASCII name is detected by its extension and signature', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles({
      name: 'Contrato ñandú (1).docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      buffer: await fs.readFile(path.join(runtime, 'fixtures', 'seeded.docx'))
    });
    await expect(page.locator('#workspace')).toBeVisible();
    await expect(page.locator('#doctext')).toContainText('Juan García Pérez');
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    const saved = await saveDownload(page, 'nonascii-docx.download.md');
    expect(saved.suggested).toBe('Contrato ñandú (1)-anonimused.md');
    expect(saved.body).not.toContain('Juan García Pérez');
  });
});

test.describe('G10 editing a non-deliverable result', () => {
  test('the edit reaches the exported file and the status stays blocked', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'seeded.pdf'));
    await expect(page.locator('#workspace')).toBeVisible();
    // Same warning scenario as principal-journeys: keeping the person leaves residual risk.
    const person = page.locator('.entity').filter({ hasText: 'Juan Perez Lopez' }).first();
    await person.getByRole('checkbox').click();
    await expect(person).toContainText('Se conserva en el texto');
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización bloqueada');
    await expect(page.locator('#download')).toBeEnabled();

    const output = page.locator('#markdown');
    await expect(output).toHaveAttribute('contenteditable', 'plaintext-only');
    await output.click();
    await page.keyboard.press('Control+End');
    await page.keyboard.type(' NOTA-REVISADA-77');
    await expect(page.locator('#edit-leak-note')).toBeHidden();
    await expect(page.locator('#result-status')).toContainText('Anonimización bloqueada');
    await expect(page.locator('#download')).toBeEnabled();

    const saved = await saveDownload(page, 'warning-edited.download.md');
    expect(saved.body).toContain('NOTA-REVISADA-77');
    expect(saved.body).toContain('[PERSONA_');
    await expect(page.locator('#result-status')).toContainText('Anonimización bloqueada');
  });
});

test.describe('G11 keyboard and focus accessibility', () => {
  test('tablist arrows, Home and End move selection and focus', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'seeded.txt'));
    await expect(page.locator('#workspace')).toBeVisible();
    const documentTab = page.locator('#tab-document-control');
    const resultTab = page.locator('#tab-result-control');
    const expectSelected = async (selected, other) => {
      await expect(selected).toHaveAttribute('aria-selected', 'true');
      await expect(selected).toBeFocused();
      await expect(other).toHaveAttribute('aria-selected', 'false');
    };
    await documentTab.focus();
    await documentTab.press('ArrowRight');
    await expectSelected(resultTab, documentTab);
    await expect(page.locator('#tab-result')).toBeVisible();
    await resultTab.press('ArrowRight'); // wraps
    await expectSelected(documentTab, resultTab);
    await documentTab.press('ArrowLeft'); // wraps backwards
    await expectSelected(resultTab, documentTab);
    await resultTab.press('Home');
    await expectSelected(documentTab, resultTab);
    await expect(page.locator('#tab-document')).toBeVisible();
    await documentTab.press('End');
    await expectSelected(resultTab, documentTab);
    await expect(page.locator('#tab-document')).toBeHidden();
  });

  test('focus stays on the same entity checkbox after a toggle re-render', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'seeded.txt'));
    await expect(page.locator('#workspace')).toBeVisible();
    const rows = page.locator('#entities [data-entity-key]');
    expect(await rows.count()).toBeGreaterThan(1);
    const key = await rows.nth(1).getAttribute('data-entity-key');
    const checkbox = page.locator('#entities [data-entity-key="' + key + '"]').getByRole('checkbox');
    await checkbox.focus();
    await page.keyboard.press('Space');
    await expect(checkbox).not.toBeChecked();
    const focused = () => page.evaluate(() => document.activeElement?.dataset?.focusTarget || null);
    expect(await focused()).toBe('checkbox:' + key);
    // Focus survived the re-render, so a second keypress re-checks the same control.
    await page.keyboard.press('Space');
    await expect(checkbox).toBeChecked();
    expect(await focused()).toBe('checkbox:' + key);
  });
});

const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

// Editing journeys (odd/tasks/e2e-session-features.md T4, G6, G7, G12) against the real Java
// server and real analysis: nothing is mocked. Documents are uploaded as in-memory text files
// so the exact content (and therefore the detections) is known.
const runtime = path.resolve(__dirname, '../.runtime');

const ERR_NOT_CONTIGUOUS = 'Selecciona un fragmento contiguo del documento que incluya la deteccion original o este dentro de ella. No se guardo el cambio.';
const ERR_PARTIAL_OVERLAP = 'La seleccion solapa parcialmente otra deteccion. Incluyela completa o reduce la seleccion. No se guardo el cambio.';

const PEOPLE_TEXT = 'Contacto: Juan García Pérez con DNI 12345678Z y móvil 600123456 hoy.\n';
const REPEAT_TEXT = 'El ñandú corre. Otro Ñandú y el ÑANDÚ, nandu; ñandúes no. DNI 12345678Z\n';

async function open(page, name, text) {
  const { baseUrl } = JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8'));
  await page.goto(baseUrl);
  await expect(page.locator('#dropzone')).toBeVisible();
  await page.locator('#file').setInputFiles({ name, mimeType: 'text/plain', buffer: Buffer.from(text, 'utf8') });
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#entities [data-entity-key]').first()).toBeVisible();
}

const row = (page, value) => page.locator('#entities [data-entity-key]')
  .filter({ has: page.locator('.entity-value', { hasText: new RegExp('^\\s*' + value + '\\s*$') }) });

async function applyAndWait(page) {
  await page.locator('#apply').click();
  await expect(page.locator('#result-status')).toContainText('Anonimización completada');
  await expect(page.locator('#apply-loading')).toBeHidden();
}

// Reads the anonymized result through the real download, not only the on-screen preview.
async function downloadText(page) {
  const pending = page.waitForEvent('download');
  await page.locator('#download').click();
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'edit-e2e-'));
  try {
    await (await pending).saveAs(path.join(dir, 'out.md'));
    return await fs.readFile(path.join(dir, 'out.md'), 'utf8');
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}

// Edits an entity's text through the real inline editor and returns after Enter.
async function editText(page, value, replacement) {
  await row(page, value).locator('.entity-value').click();
  const editor = page.locator('#entities input.entity-editor');
  await expect(editor).toBeFocused();
  await editor.fill(replacement);
  await editor.press('Enter');
}

test.describe('G6 entity type and inline text edits', () => {
  test('changing the type through the row chip changes the label in the output', async ({ page }) => {
    await open(page, 'people.txt', PEOPLE_TEXT);
    const dni = row(page, '12345678Z');
    await expect(dni.locator('select.chip')).toHaveValue('DNI');

    await dni.locator('select.chip').selectOption('CODIGO');
    await expect(row(page, '12345678Z').locator('select.chip')).toHaveValue('CODIGO');

    await applyAndWait(page);
    await expect(page.locator('#markdown')).toContainText('[CODIGO_001]');
    await expect(page.locator('#markdown')).not.toContainText('[DNI_001]');
    await expect(page.locator('#markdown')).not.toContainText('12345678Z');
    const downloaded = await downloadText(page);
    expect(downloaded).toContain('con DNI [CODIGO_001] y');
    expect(downloaded).not.toContain('12345678Z');
    expect(downloaded).toContain('[PERSONA_001]');
  });

  test('a non-contiguous text edit shows the exact error and leaves the entity unchanged', async ({ page }) => {
    await open(page, 'people.txt', PEOPLE_TEXT);
    await editText(page, 'Juan García Pérez', 'Juan Pérez');

    await expect(page.locator('#error')).toBeVisible();
    await expect(page.locator('#error')).toContainText(ERR_NOT_CONTIGUOUS);
    await expect(row(page, 'Juan García Pérez')).toHaveCount(1);
    await expect(page.locator('#entities input.entity-editor')).toHaveCount(0);

    await applyAndWait(page);
    await expect(page.locator('#markdown')).toContainText('Contacto: [PERSONA_001] con DNI [DNI_001] y');
    await expect(page.locator('#markdown')).not.toContainText('Juan García Pérez');
  });

  test('a selection partially overlapping another detection shows the exact error and changes nothing', async ({ page }) => {
    await open(page, 'people.txt', PEOPLE_TEXT);
    // "12345678Z y móvil 600" covers the whole DNI but only the start of the phone detection.
    await editText(page, '12345678Z', '12345678Z y móvil 600');

    await expect(page.locator('#error')).toBeVisible();
    await expect(page.locator('#error')).toContainText(ERR_PARTIAL_OVERLAP);
    await expect(row(page, '12345678Z')).toHaveCount(1);
    await expect(row(page, '600123456')).toHaveCount(1);

    await applyAndWait(page);
    await expect(page.locator('#markdown')).toContainText('con DNI [DNI_001] y móvil [TELEFONO_001] hoy.');
  });

  test('a valid narrowing edit is accepted without error (control for the error paths)', async ({ page }) => {
    await open(page, 'people.txt', PEOPLE_TEXT);
    await editText(page, 'Juan García Pérez', 'Juan García');

    await expect(page.locator('#error')).toBeHidden();
    await expect(row(page, 'Juan García')).toHaveCount(1);
    await applyAndWait(page);
    await expect(page.locator('#markdown')).toContainText('Contacto: [PERSONA_001] Pérez con DNI');
  });
});

test.describe('G7 manual entity from a real mouse selection', () => {
  test('marks every accented and repeated occurrence and none remain after anonymizing', async ({ page }) => {
    await open(page, 'repeat.txt', REPEAT_TEXT);

    // Real double click on the first word selects it in the browser; the selection text is what
    // the user then enters as the manual entity (the product has no selection-to-entity handler).
    const point = await page.locator('#doctext').evaluate(root => {
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const index = node.data.indexOf('ñandú corre');
        if (index < 0) continue;
        const range = document.createRange();
        range.setStart(node, index);
        range.setEnd(node, index + 'ñandú'.length);
        const rect = range.getBoundingClientRect();
        return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
      }
      return null;
    });
    expect(point).not.toBeNull();
    await page.mouse.dblclick(point.x, point.y);
    const selected = await page.evaluate(() => window.getSelection().toString().trim());
    expect(selected).toBe('ñandú');

    await page.getByRole('button', { name: 'Añadir entidad a anonimizar' }).click();
    const input = page.locator('#entities input.entity-editor');
    await expect(input).toBeVisible();
    await input.fill(selected);
    await page.getByRole('button', { name: 'Guardar entidad manual' }).click();

    const manual = row(page, 'ñandú');
    await expect(manual).toHaveCount(1);
    await expect(manual.locator('select.chip')).toHaveValue('TEXTO');
    // ñandú, Ñandú, ÑANDÚ and nandu; "ñandúes" is a different word and must not be marked.
    await expect(manual.locator('.entity-meta').first()).toHaveText('4');
    // Four manual marks plus the DNI detection.
    await expect(page.locator('#doctext .hl')).toHaveCount(5);
    await expect(page.locator('#error')).toBeHidden();

    await applyAndWait(page);
    const expected = 'El [TEXTO_001] corre. Otro [TEXTO_001] y el [TEXTO_001], [TEXTO_001]; ñandúes no. DNI [DNI_001]';
    expect(await page.locator('#markdown').innerText()).toContain(expected);
    const downloaded = await downloadText(page);
    expect(downloaded).toContain(expected);
    expect(downloaded).not.toMatch(/(^|[^\p{L}])(ñandú|ñandu|nandu)([^\p{L}]|$)/iu);
  });
});

// The product has acceptAll() (app.js:749) but nothing binds it to the UI, so the supported
// "accept all" path is the bulk "Marcar todos" button exercised here.
test.describe('G12 re-checking after rejecting entities', () => {
  test('re-checking every entity updates every checkbox, mark and counter, and the output', async ({ page }) => {
    await open(page, 'people.txt', PEOPLE_TEXT);
    const boxes = page.locator('#entities [data-entity-key] input[type=checkbox]');
    const stats = '3 entidades · 3 candidatos · 1 página';
    await expect(boxes).toHaveCount(3);
    await expect(page.locator('#stats')).toHaveText(stats);
    for (let i = 0; i < 3; i++) await expect(boxes.nth(i)).toBeChecked();
    await expect(page.locator('#doctext .hl')).toHaveCount(3);

    await boxes.nth(0).uncheck();
    await boxes.nth(1).uncheck();
    await expect(boxes.nth(0)).not.toBeChecked();
    await expect(boxes.nth(1)).not.toBeChecked();
    await expect(boxes.nth(2)).toBeChecked();
    await expect(page.locator('#doctext .hl')).toHaveCount(1);
    await expect(page.locator('#entities [data-entity-key].off')).toHaveCount(2);
    await expect(page.locator('#bulk-uncheck')).toHaveText('Desmarcar todos');
    await expect(page.locator('#stats')).toHaveText(stats);

    // Rejected values stay in the output; only the checked entity is anonymized.
    await applyAndWait(page);
    const partial = await page.locator('#markdown').innerText();
    expect(partial.split('\n')[0]).toBe('Contacto: Juan García Pérez con DNI 12345678Z y móvil [TELEFONO_001] hoy.');

    // Uncheck the last one: every listed entity is unchecked, so the bulk button re-checks all.
    await boxes.nth(2).uncheck();
    await expect(page.locator('#bulk-uncheck')).toHaveText('Marcar todos');
    await expect(page.locator('#doctext .hl')).toHaveCount(0);
    await page.locator('#bulk-uncheck').click();
    for (let i = 0; i < 3; i++) await expect(boxes.nth(i)).toBeChecked();
    await expect(page.locator('#entities [data-entity-key].off')).toHaveCount(0);
    await expect(page.locator('#doctext .hl')).toHaveCount(3);
    await expect(page.locator('#bulk-uncheck')).toHaveText('Desmarcar todos');
    await expect(page.locator('#stats')).toHaveText(stats);

    await applyAndWait(page);
    await expect(page.locator('#markdown')).toContainText(
      'Contacto: [PERSONA_001] con DNI [DNI_001] y móvil [TELEFONO_001] hoy.');
    const downloaded = await downloadText(page);
    for (const original of ['Juan García Pérez', '12345678Z', '600123456']) expect(downloaded).not.toContain(original);
  });
});

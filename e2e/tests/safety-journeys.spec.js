const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

// Safety journeys (odd/tasks/e2e-session-features.md T3, G1-G5) against the real Java server.
// Only the G2 delay and the G5 expired-analysis body use page.route; everything else is real.
const runtime = path.resolve(__dirname, '../.runtime');
const CONFIRM = 'Volver a anonimizar descartará tus ediciones del documento anonimizado. ¿Continuar?';

async function baseUrl() {
  return JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8')).baseUrl;
}

async function open(page) {
  await page.goto(await baseUrl());
  await expect(page.locator('#dropzone')).toBeVisible();
}

async function uploadFixture(page, name = 'seeded.txt') {
  await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', name));
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#entities [data-entity-key]').first()).toBeVisible();
}

function textFile(name, text) {
  return { name, mimeType: 'text/plain', buffer: Buffer.from(text, 'utf8') };
}

async function applyAndWait(page) {
  await page.locator('#apply').click();
  await expect(page.locator('#result-status')).toContainText('Anonimización completada');
  await expect(page.locator('#apply-loading')).toBeHidden();
}

// Replaces the editable result with `text` through real typing so the input handler fires.
async function typeInResult(page, text) {
  const output = page.locator('#markdown');
  await output.click();
  await page.keyboard.press('Control+End');
  await page.keyboard.type(text);
}

const A_TEXT = 'Documento A\nComparece Pedro Alonso Vidal con DNI 11111111H.\n';
const B_TEXT = 'Documento B\nComparece Lucía Romero Campos con DNI 22222222J.\n';

test.describe('G1 result edit leak guard', () => {
  test('blocks download and copy while an original value is typed into the result', async ({ page }) => {
    await open(page);
    await uploadFixture(page);
    await applyAndWait(page);
    const note = page.locator('#edit-leak-note');
    await expect(note).toBeHidden();
    await expect(page.locator('#download')).toBeEnabled();
    await expect(page.locator('#copy')).toBeEnabled();

    await typeInResult(page, ' 12345678Z');
    await expect(note).toBeVisible();
    await expect(note).toContainText('vuelve a contener un dato anonimizado');
    await expect(page.locator('#download')).toBeDisabled();
    await expect(page.locator('#copy')).toBeDisabled();

    for (let i = 0; i < ' 12345678Z'.length; i++) await page.keyboard.press('Backspace');
    await expect(note).toBeHidden();
    await expect(page.locator('#download')).toBeEnabled();
    await expect(page.locator('#copy')).toBeEnabled();
  });
});

test.describe('G2 stale analysis responses', () => {
  test('a slow analysis of file A never replaces the newer document B', async ({ page }) => {
    await open(page);
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    let held = 0;
    await page.route('**/api/analyze', async route => {
      if (held++ === 0) {
        // Forward to the real server now, but hold the response until the gate opens.
        const response = await route.fetch();
        await gate;
        await route.fulfill({ response });
      } else {
        await route.continue();
      }
    });

    await page.locator('#file').setInputFiles(textFile('a.txt', A_TEXT));
    await expect(page.locator('#loading')).toBeVisible();
    await page.locator('#file').setInputFiles(textFile('b.txt', B_TEXT));
    await expect(page.locator('#workspace')).toBeVisible();
    await expect(page.locator('#doctext')).toContainText('Lucía Romero Campos');
    await expect(page.locator('#entities')).toContainText('22222222J');

    release();
    // Let the superseded response land, then prove it was ignored.
    await page.waitForTimeout(500);
    await expect(page.locator('#doctext')).toContainText('Documento B');
    await expect(page.locator('#doctext')).not.toContainText('Pedro Alonso Vidal');
    await expect(page.locator('#entities')).not.toContainText('11111111H');
    await expect(page.locator('#entities')).toContainText('22222222J');
    await expect(page.locator('#loading')).toBeHidden();
  });

  test('going home while an analysis is loading leaves the landing without a document', async ({ page }) => {
    await open(page);
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    await page.route('**/api/analyze', async route => {
      const response = await route.fetch();
      await gate;
      await route.fulfill({ response });
    });

    await page.locator('#file').setInputFiles(textFile('a.txt', A_TEXT));
    await expect(page.locator('#loading')).toBeVisible();
    await page.locator('#brand-home').click();
    await expect(page.locator('#loading')).toBeHidden();

    release();
    await page.waitForTimeout(500);
    await expect(page.locator('#workspace')).toBeHidden();
    await expect(page.locator('#dropzone')).toBeVisible();
    await expect(page.locator('#doctext')).toHaveText('');
    await expect(page.locator('#entities [data-entity-key]')).toHaveCount(0);
    await expect(page.locator('#loading')).toBeHidden();
  });
});

test.describe('G3 re-anonymize confirmation', () => {
  test('dismiss keeps the edit and sends nothing; accept regenerates without the edit', async ({ page }) => {
    await open(page);
    await uploadFixture(page);
    await applyAndWait(page);
    const applyRequests = [];
    page.on('request', request => { if (request.url().endsWith('/api/apply')) applyRequests.push(request); });

    await typeInResult(page, ' EDICION-MANUAL-XYZ');
    await expect(page.locator('#markdown')).toContainText('EDICION-MANUAL-XYZ');

    const messages = [];
    let accept = false;
    page.on('dialog', dialog => {
      messages.push(dialog.message());
      return accept ? dialog.accept() : dialog.dismiss();
    });

    await page.locator('#apply').click();
    await expect.poll(() => messages.length).toBe(1);
    expect(messages[0]).toBe(CONFIRM);
    await page.waitForTimeout(300);
    expect(applyRequests).toHaveLength(0);
    await expect(page.locator('#markdown')).toContainText('EDICION-MANUAL-XYZ');
    await expect(page.locator('#apply')).toBeEnabled();

    accept = true;
    const applied = page.waitForResponse(response => response.url().endsWith('/api/apply'));
    await page.locator('#apply').click();
    expect((await applied).status()).toBe(200);
    expect(messages).toEqual([CONFIRM, CONFIRM]);
    expect(applyRequests).toHaveLength(1);
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    await expect(page.locator('#apply-loading')).toBeHidden();
    await expect(page.locator('#markdown')).not.toContainText('EDICION-MANUAL-XYZ');
    await expect(page.locator('#markdown')).not.toContainText('12345678Z');
    await expect(page.locator('#download')).toBeEnabled();
  });
});

test.describe('G4 logo returns home', () => {
  test('clears document, entities and result, and the same file can be analyzed again', async ({ page }) => {
    await open(page);
    await uploadFixture(page);
    await applyAndWait(page);
    await expect(page.locator('#doctext')).toContainText('Juan García Pérez');

    await page.locator('#brand-home').click();
    await expect(page.locator('#workspace')).toBeHidden();
    await expect(page.locator('#dropzone')).toBeVisible();
    await expect(page.locator('#dropzone')).not.toHaveClass(/compact/);
    await expect(page.locator('#document-chip')).toBeHidden();
    await expect(page.locator('#doctext')).toHaveText('');
    await expect(page.locator('#entities [data-entity-key]')).toHaveCount(0);
    await expect(page.locator('#entities')).toHaveText('');
    await expect(page.locator('#markdown')).not.toContainText('PERSONA');
    await expect(page.locator('#file')).toHaveValue('');

    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'seeded.txt'));
    await expect(page.locator('#workspace')).toBeVisible();
    await expect(page.locator('#doctext')).toContainText('Juan García Pérez');
    await expect(page.locator('#entities [data-entity-key]').first()).toBeVisible();
    // The previous result must not survive the round trip.
    await expect(page.locator('#download')).toBeDisabled();
  });
});

test.describe('G5 server errors', () => {
  test('the real server answers an unknown analysis id with 404 and the documented body', async ({ request }) => {
    const response = await request.post(await baseUrl() + '/api/apply', {
      headers: { 'X-Job-Id': 'no-such-job', 'Content-Type': 'text/plain' }, data: ''
    });
    expect(response.status()).toBe(404);
    expect(await response.json()).toEqual({
      error: 'El analisis expiro o no existe. Vuelve a subir el documento.'
    });
  });

  test('an expired analysis on apply shows the failure, closes the overlay and Anonimizar works again', async ({ page }) => {
    await open(page);
    await uploadFixture(page);
    // The job store has no test hook to expire a job, so replay the verified real 404 response.
    let expire = true;
    await page.route('**/api/apply', route => expire
      ? route.fulfill({
        status: 404, contentType: 'application/json; charset=utf-8',
        headers: { 'Cache-Control': 'no-store' },
        body: JSON.stringify({ error: 'El analisis expiro o no existe. Vuelve a subir el documento.' })
      })
      : route.continue());

    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('No se pudo completar la anonimización');
    await expect(page.locator('#apply-loading')).toBeHidden();
    await expect(page.locator('#apply')).toBeEnabled();
    await expect(page.locator('#download')).toBeDisabled();

    expire = false;
    await applyAndWait(page);
    await expect(page.locator('#download')).toBeEnabled();
  });

  test('a >50 MB upload is rejected with the server error and the UI stays usable', async ({ page }) => {
    const big = path.join(runtime, 'safety-big-upload.txt');
    const handle = await fs.open(big, 'w');
    try {
      await handle.truncate(50 * 1024 * 1024 + 1024); // sparse where supported: fast to create
    } finally {
      await handle.close();
    }
    try {
      await open(page);
      await page.locator('#file').setInputFiles(big);
      await expect(page.locator('#error')).toBeVisible();
      await expect(page.locator('#error')).toContainText('El fichero supera el limite de 50 MB.');
      await expect(page.locator('#loading')).toBeHidden();
      await expect(page.locator('#workspace')).toBeHidden();

      await uploadFixture(page);
      await expect(page.locator('#error')).toBeHidden();
      await expect(page.locator('#doctext')).toContainText('Juan García Pérez');
    } finally {
      await fs.rm(big, { force: true });
    }
  });
});

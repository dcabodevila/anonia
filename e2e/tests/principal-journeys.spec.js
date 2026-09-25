const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

const root = path.resolve(__dirname, '..', '..');
const runtime = path.join(root, 'e2e', '.runtime');

async function server() {
  return JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8'));
}

async function open(page) {
  const { baseUrl } = await server();
  await page.goto(baseUrl);
  await expect(page.locator('#dropzone')).toBeVisible();
}

async function upload(page, name = 'seeded.pdf') {
  await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', name));
  await expect(page.locator('#workspace')).toBeVisible();
}

async function waitForAnalysisOutcome(page) {
  await expect.poll(async () => (await page.locator('#workspace').isVisible())
    || (await page.locator('#error').isVisible())).toBe(true);
}

function entity(page, text) {
  return page.locator('.entity').filter({ hasText: text }).first();
}

async function saveDownload(page, button, name) {
  const download = page.waitForEvent('download');
  await button.click();
  const saved = await download;
  const output = path.join(runtime, name);
  await saved.saveAs(output);
  return fs.readFile(output, 'utf8');
}

test.describe('principal anonymization journeys against the local Java server', () => {
  test.describe.configure({ mode: 'serial' });

  test('serves health, static UI, and approved local assets', async ({ page, browser }) => {
    await open(page);
    await expect(page).toHaveTitle('anonimuse');
    const responses = await page.evaluate(async () => {
      const health = await fetch('/health');
      const app = await fetch('/app.js');
      const logo = await fetch('/anonimuse-logo.png');
      return {
        health: { status: health.status, body: await health.text() },
        app: { status: app.status, cache: app.headers.get('cache-control'), body: await app.text() },
        logo: { status: logo.status, type: logo.headers.get('content-type') }
      };
    });
    expect(responses.health).toEqual({ status: 200, body: 'ok' });
    expect(responses.app.status).toBe(200);
    expect(responses.app.cache).toBe('no-store');
    expect(responses.app.body).toContain('async function analyze');
    expect(responses.logo).toEqual({ status: 200, type: 'image/png' });
    await fs.writeFile(path.join(runtime, 'environment.json'), JSON.stringify({
      node: process.version, browser: browser.version()
    }, null, 2));
  });

  test('switches document and result tabs through real ARIA controls', async ({ page }) => {
    await open(page);
    await upload(page);
    const documentTab = page.locator('#tab-document-control');
    const resultTab = page.locator('#tab-result-control');
    await expect(documentTab).toHaveAttribute('aria-selected', 'true');
    await expect(resultTab).toHaveAttribute('aria-selected', 'false');
    await resultTab.click();
    await expect(resultTab).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#tab-result')).toBeVisible();
    await expect(page.locator('#tab-document')).toBeHidden();
    await resultTab.press('ArrowLeft');
    await expect(documentTab).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#tab-document')).toBeVisible();
    await page.locator('#compare').click();
    await expect(page.locator('#compare')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('#tab-result')).toBeVisible();
  });

  test('links proportional reading progress only while comparing', async ({ page }) => {
    await open(page);
    await upload(page);
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    await page.locator('#compare').click();
    await expect(page.locator('#compare')).toHaveAttribute('aria-pressed', 'true');
    const original = page.locator('#doctext');
    const output = page.locator('#markdown');
    await expect(original).toBeVisible();
    await expect(output).toBeVisible();

    // Extend only the rendered reading surfaces, leaving the document and result intact.
    await page.evaluate(() => {
      for (const id of ['doctext', 'markdown']) {
        const extension = document.createElement('div');
        extension.textContent = ('Neutral scroll padding.\n').repeat(250);
        extension.style.whiteSpace = 'pre-line';
        document.getElementById(id).append(extension);
      }
    });
    const metrics = async (locator) => locator.evaluate(el => ({
      top: el.scrollTop, range: el.scrollHeight - el.clientHeight
    }));
    const move = async (locator, progress) => locator.evaluate((el, fraction) => {
      el.scrollTop = (el.scrollHeight - el.clientHeight) * fraction;
    }, progress);
    const expectProgress = async (locator, fraction) => {
      await expect.poll(async () => {
        const { top, range } = await metrics(locator);
        return range > 0 ? top / range : -1;
      }).toBeGreaterThan(fraction - 0.03);
      const { top, range } = await metrics(locator);
      expect(range).toBeGreaterThan(100);
      expect(top / range).toBeLessThan(fraction + 0.03);
    };
    expect((await metrics(original)).range).toBeGreaterThan(100);
    expect((await metrics(output)).range).toBeGreaterThan(100);
    await move(original, 0.65);
    await expectProgress(original, 0.65);
    await expectProgress(output, 0.65);
    await move(output, 0.25);
    await expectProgress(output, 0.25);
    await expectProgress(original, 0.25);

    await page.locator('#compare').click();
    await expect(page.locator('#compare')).toHaveAttribute('aria-pressed', 'false');
    await page.locator('#tab-document-control').click();
    const outputBefore = await metrics(output);
    await move(original, 0.8);
    await expectProgress(original, 0.8);
    expect((await metrics(output)).top).toBeCloseTo(outputBefore.top, 0);
    await page.locator('#tab-result-control').click();
    const originalBefore = await metrics(original);
    await move(output, 0.45);
    expect((await metrics(output)).top).toBeGreaterThan(0);
    expect((await metrics(original)).top).toBeCloseTo(originalBefore.top, 0);
  });

  test('filters entities and navigates actual multiple occurrences', async ({ page }) => {
    await open(page);
    await upload(page);
    const rows = page.locator('.entity[data-entity-key]');
    const initialCount = await rows.count();
    expect(initialCount).toBeGreaterThan(1);
    await page.locator('#entity-search').fill('juan.perez@example.com');
    await expect(entity(page, 'juan.perez@example.com')).toBeVisible();
    await expect(entity(page, 'Juan Perez Lopez')).toBeHidden();
    await page.locator('#entity-search').fill('');
    await page.locator('#entity-type-filter').selectOption('DNI');
    await expect(rows).not.toHaveCount(0);
    expect(await rows.locator('.chip').evaluateAll(chips => chips.map(chip => chip.value)))
      .toEqual(Array(await rows.count()).fill('DNI'));
    await page.locator('#entity-type-filter').selectOption('');
    await expect(rows).toHaveCount(initialCount);

    const multiOccurrence = page.locator('.entity').filter({
      has: page.locator('.entity-meta', { hasText: /[2-9] apariciones/ })
    }).first();
    await expect(multiOccurrence).toBeVisible();
    await multiOccurrence.getByRole('button', { name: 'Ubicar' }).click();
    await expect(page.locator('#location-status')).toContainText(/aparición 1 de [2-9]/);
    const next = multiOccurrence.getByRole('button', { name: 'Siguiente' });
    await expect(next).toBeVisible();
    await next.click();
    await expect(page.locator('#location-status')).toContainText(/aparición 2 de [2-9]/);
    await expect(page.locator('.active-location')).toBeVisible();
  });

  test('cancels manual input, edits an entity, and delivers verified output', async ({ page }) => {
    await open(page);
    await upload(page);
    await page.getByRole('button', { name: 'Añadir entidad anonimizar' }).click();
    const manual = page.getByRole('textbox', { name: /Texto a anonimizar/ });
    // A word no detector proposes, so only a saved manual entry could create it.
    await manual.fill('arrendamiento');
    await manual.press('Escape');
    await expect(page.getByRole('button', { name: 'Añadir entidad anonimizar' })).toBeVisible();
    await expect(entity(page, 'arrendamiento')).toHaveCount(0);

    const email = entity(page, 'juan.perez@example.com');
    await email.locator('.entity-value').click();
    const editor = page.getByRole('textbox', { name: /Texto a anonimizar/ });
    await editor.fill('correo electronico juan.perez@example.com');
    await editor.press('Enter');
    await expect(entity(page, 'correo electronico juan.perez@example.com')).toBeVisible();

    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    await expect(page.locator('#download')).toBeEnabled();
    const markdown = await page.locator('#markdown').innerText();
    expect(markdown).toContain('[PERSONA_');
    // Long digit runs are anonymized as codes even inside alphanumeric references.
    expect(markdown).not.toContain('87654321');
    expect(markdown).toContain('[CODIGO_');
    expect(markdown).not.toContain('Juan Perez Lopez');
    expect(markdown).not.toContain('12345678Z');
    expect(markdown).not.toContain('juan.perez@example.com');

    const downloaded = await saveDownload(page, page.locator('#download'), 'verified-download.md');
    expect(downloaded).toContain('[PERSONA_');
    expect(downloaded).not.toContain('juan.perez@example.com');
  });

  test('creates, deselects, reselects, and delivers a manual entity', async ({ page }) => {
    await open(page);
    await upload(page);
    await page.getByRole('button', { name: 'Añadir entidad anonimizar' }).click();
    const manual = page.getByRole('textbox', { name: /Texto a anonimizar/ });
    await manual.fill('arrendamiento');
    await page.getByRole('button', { name: 'Guardar' }).click();
    const added = entity(page, 'arrendamiento');
    await expect(added).toBeVisible();
    const selection = added.getByRole('checkbox');
    await expect(selection).toBeChecked();
    await selection.click();
    await expect(added).toContainText('Se conserva en el texto');
    await selection.click();
    await expect(selection).toBeChecked();
    await expect(added).not.toContainText('Se conserva en el texto');
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    const output = await saveDownload(page, page.locator('#download'), 'manual-download.md');
    expect(output).not.toContain('arrendamiento');
    expect(output).toContain('[TEXTO_');
  });

  test('copies verified output when Chromium clipboard permissions are available', async ({ page, context }) => {
    await open(page);
    await upload(page);
    await page.locator('#apply').click();
    await expect(page.locator('#copy')).toBeEnabled();
    const { baseUrl } = await server();
    try {
      await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: baseUrl });
    } catch (error) {
      test.info().annotations.push({ type: 'environment', description: String(error) });
      test.skip('Chromium clipboard permissions are unavailable in this environment.');
    }
    await page.locator('#copy').click();
    await expect(page.locator('#copy-label')).toHaveText('Copiado');
    const clipboard = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboard).toContain('[PERSONA_');
    expect(clipboard).not.toContain('juan.perez@example.com');
  });

  test('blocks ordinary delivery but copies preview and exports the explicit warning artifact', async ({ page, context }) => {
    await open(page);
    await upload(page);
    const person = entity(page, 'Juan Perez Lopez');
    await person.getByRole('checkbox').click();
    await expect(person).toContainText('Se conserva en el texto');
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización bloqueada');
    await expect(page.locator('#download')).toBeDisabled();
    await expect(page.locator('#copy')).toBeEnabled();
    await expect(page.locator('#copy-label')).toHaveText('Copiar');
    await expect(page.locator('#warning-note')).toContainText('copiar');
    await expect(page.locator('#warning-download')).toBeEnabled();
    const preview = await page.locator('#markdown').innerText();
    const { baseUrl } = await server();
    try {
      await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: baseUrl });
    } catch (error) {
      test.info().annotations.push({ type: 'environment', description: String(error) });
      test.skip('Chromium clipboard permissions are unavailable in this environment.');
    }
    await page.locator('#copy').click();
    await expect(page.locator('#copy-label')).toHaveText('Copiado');
    expect((await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')).toBe(preview);
    const warning = await saveDownload(page, page.locator('#warning-download'), 'warning-download.md');
    expect(warning).toContain('[PERSONA_');
    expect(warning).toContain(preview);
    expect(warning).not.toContain('juan.perez@example.com');
  });

  test('replaces a successful result and clears its prior delivery state', async ({ page }) => {
    await open(page);
    await upload(page);
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    await expect(page.locator('#markdown')).toContainText('[PERSONA_');
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'replacement.pdf'));
    await expect(page.locator('#workspace')).toBeVisible();
    await expect(page.locator('#drop-title')).toContainText('replacement.pdf');
    await expect(page.locator('#result-status')).toContainText('Resultado pendiente');
    await expect(page.locator('#markdown')).toHaveText('Pulsa «Anonimizar» para generar el resultado.');
    await expect(page.locator('#download')).toBeDisabled();
  });

  test('rejects unsupported, empty, and corrupt documents without retaining a workspace', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'invalid.txt'));
    await expect(page.locator('#error')).toContainText('Solo se admiten documentos PDF y fotos JPEG o PNG');
    await expect(page.locator('#workspace')).toBeHidden();
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'empty.pdf'));
    await expect(page.locator('#error')).toContainText('Cuerpo vacio');
    await expect(page.locator('#workspace')).toBeHidden();
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'corrupt.pdf'));
    await expect(page.locator('#error')).not.toHaveText('');
    await expect(page.locator('#error')).not.toContainText('Solo se admiten documentos PDF y fotos JPEG o PNG');
    await expect(page.locator('#workspace')).toBeHidden();
  });

  test('anonymizes and downloads the synthetic OCR JPEG, or records a proven missing dependency', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'ocr-seeded.jpg'));
    await waitForAnalysisOutcome(page);
    if (!await page.locator('#workspace').isVisible()) {
      const message = await page.locator('#error').innerText();
      const unavailable = 'OCR local no disponible. Instala Tesseract y el modelo espanol (spa).';
      if (message === unavailable) test.skip(message);
      throw new Error(`JPEG OCR did not reach a usable workspace: ${message}`);
    }
    await expect(page.locator('#doctext')).toContainText(/JUAN|PEREZ|12345678/i);
    await page.locator('#apply').click();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    const output = await saveDownload(page, page.locator('#download'), 'ocr-jpeg-download.md');
    expect(output).toContain('[PERSONA_');
    expect(output).not.toContain('JUAN PEREZ LOPEZ');
    expect(output).not.toContain('12345678Z');
  });

  test('anonymizes and downloads the synthetic OCR PNG, or records a proven missing dependency', async ({ page }) => {
    await open(page);
    await page.locator('#file').setInputFiles(path.join(runtime, 'fixtures', 'ocr-seeded.png'));
    await waitForAnalysisOutcome(page);
    const statusPath = path.join(runtime, 'ocr-status.json');

    if (await page.locator('#workspace').isVisible()) {
      await expect(page.locator('#doctext')).toContainText(/JUAN|PEREZ|12345678/i);
      await page.locator('#apply').click();
      await expect(page.locator('#result-status')).toContainText('Anonimización completada');
      const output = await saveDownload(page, page.locator('#download'), 'ocr-download.md');
      expect(output).toContain('[PERSONA_');
      expect(output).not.toContain('JUAN PEREZ LOPEZ');
      expect(output).not.toContain('12345678Z');
      await fs.writeFile(statusPath, JSON.stringify({
        status: 'available', jpeg: 'verified-download', png: 'verified-download'
      }, null, 2));
      return;
    }

    const message = await page.locator('#error').innerText();
    const unavailable = 'OCR local no disponible. Instala Tesseract y el modelo espanol (spa).';
    if (message === unavailable) {
      await fs.writeFile(statusPath, JSON.stringify({
        status: 'blocked', fixture: 'ocr-seeded.png', reason: message
      }, null, 2));
      test.skip(message);
    }
    await fs.writeFile(statusPath, JSON.stringify({
      status: 'failed', fixture: 'ocr-seeded.png', reason: message
    }, null, 2));
    throw new Error(`OCR did not reach a usable workspace: ${message}`);
  });
});

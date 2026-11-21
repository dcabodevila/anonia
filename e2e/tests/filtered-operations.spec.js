const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

// Filtered entity operations in a real browser: text filter syntax (S1), bulk check/uncheck of the
// listed entities (S2), loading overlay while applying (S3) and filters reset when locating from
// the document (S4). The analysis is mocked so entity values are exact; the page is the real one.
const runtime = path.resolve(__dirname, '../.runtime');

function review(entities) {
  const text = entities.map(entity => entity.value).join(' | ');
  let from = 0;
  const detections = entities.map((entity, index) => {
    const start = text.indexOf(entity.value, from);
    from = start + entity.value.length;
    return { id: 'd' + index, type: entity.type || 'PERSONA', start, end: from,
      entityKey: 'k' + index, provenance: 'REGEX', confidence: 1 };
  });
  return { jobId: 'filtered', text, pageCount: 1, elapsedMs: 1,
    types: ['PERSONA', 'CP', 'CODIGO'], detections };
}

const applyOk = { deliverable: true, markdown: 'resultado', substitutions: 1, entities: 1, labels: [], findings: [] };

async function open(page, entities, { apply } = {}) {
  const { baseUrl } = JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8'));
  await page.route('**/api/analyze', route => route.fulfill({ json: review(entities) }));
  await page.route('**/api/apply', apply || (route => route.fulfill({ json: applyOk })));
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.goto(baseUrl);
  await page.locator('#file').setInputFiles({ name: 'filtered.pdf', mimeType: 'application/pdf',
    buffer: Buffer.from('fixture') });
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#entities [data-entity-key]')).toHaveCount(entities.length);
}

const rows = page => page.locator('#entities [data-entity-key]');
const box = (page, key) => page.locator(`#entities [data-entity-key="${key}"] input[type=checkbox]`);
const bulk = page => page.locator('#bulk-uncheck');

async function listed(page) {
  return (await page.locator('#entities [data-entity-key] .entity-value').allTextContents())
    .map(value => value.trim()).sort();
}

async function search(page, query) {
  await page.locator('#entity-search').fill(query);
}

test.describe('S1 entity text filter syntax', () => {
  const NAMES = ['Ana', 'Mariana', 'Anabel', 'Ana-Maria', 'Luis Perez', 'a*b', 'Zara'];
  const CASES = [
    ['plain substring, case-insensitive', 'ANA', ['Ana', 'Ana-Maria', 'Anabel', 'Mariana']],
    ['* anchors at the start', 'ana*', ['Ana', 'Ana-Maria', 'Anabel']],
    ['* anchors at the end', '*ana', ['Ana', 'Mariana']],
    ['* on both sides is contains', '*ana*', ['Ana', 'Ana-Maria', 'Anabel', 'Mariana']],
    ['% behaves like *', '%ana%', ['Ana', 'Ana-Maria', 'Anabel', 'Mariana']],
    ['% anchors at the start', 'ana%', ['Ana', 'Ana-Maria', 'Anabel']],
    ['? matches exactly one character', 'a?a', ['Ana']],
    ['_ matches exactly one character', 'an_', ['Ana']],
    ['wildcard in the middle', 'a*l', ['Anabel']],
    ['quoted text is an exact match', '"ana"', ['Ana']],
    ['quoted text is literal', '"a*b"', ['a*b']],
    ['an unclosed quote is literal text', '"ana', []],
    ['a hyphen with a wildcard does not break', 'ana-*', ['Ana-Maria']],
    ['regex metacharacters are literal', 'a(', []]
  ];

  for (const [label, query, expected] of CASES) {
    test(`${label}: ${query}`, async ({ page }) => {
      await open(page, NAMES.map(value => ({ value })));
      await search(page, query);
      await expect(rows(page)).toHaveCount(expected.length);
      expect(await listed(page)).toEqual(expected);
      await expect(page.locator('#error')).toBeHidden();
    });
  }

  test('a long multi-wildcard query on a near match stays responsive', async ({ page }) => {
    const long = 'a'.repeat(4000);
    await open(page, [{ value: long }, { value: 'Zara' }]);
    const query = '*a'.repeat(12) + '*b';
    const started = Date.now();
    await search(page, query);
    await expect(rows(page)).toHaveCount(0);
    // The page still answers and clearing the filter restores both rows.
    await search(page, '');
    await expect(rows(page)).toHaveCount(2);
    expect(Date.now() - started).toBeLessThan(5000);
  });
});

test.describe('S2 bulk button acts on listed entities only', () => {
  // k0..k2 are PERSONA, k3..k4 are CP.
  const ENTITIES = [
    { value: 'Ana Lopez' }, { value: 'Beatriz Gil' }, { value: 'Carlos Ruiz' },
    { value: '28001', type: 'CP' }, { value: '08002', type: 'CP' }
  ];

  test('uncheck then re-check only what the type filter lists', async ({ page }) => {
    await open(page, ENTITIES);
    await expect(bulk(page)).toHaveText('Desmarcar todos');
    await page.locator('#entity-type-filter').selectOption('PERSONA');
    await expect(rows(page)).toHaveCount(3);

    await bulk(page).click();
    for (const key of ['k0', 'k1', 'k2']) await expect(box(page, key)).not.toBeChecked();
    await expect(bulk(page)).toHaveText('Marcar todos');
    await expect(bulk(page)).toBeEnabled();

    // Hidden entities kept their state.
    await page.locator('#entity-type-filter').selectOption('CP');
    await expect(rows(page)).toHaveCount(2);
    await expect(box(page, 'k3')).toBeChecked();
    await expect(box(page, 'k4')).toBeChecked();
    await expect(bulk(page)).toHaveText('Desmarcar todos');

    // Marcar todos re-checks only the listed entities.
    await page.locator('#entity-type-filter').selectOption('PERSONA');
    await bulk(page).click();
    for (const key of ['k0', 'k1', 'k2']) await expect(box(page, key)).toBeChecked();
    await expect(bulk(page)).toHaveText('Desmarcar todos');
  });

  test('the text filter combines with the type filter and hidden rows keep their state', async ({ page }) => {
    await open(page, ENTITIES);
    await search(page, 'a*');
    await page.locator('#entity-type-filter').selectOption('PERSONA');
    await expect(rows(page)).toHaveCount(1); // only "Ana Lopez" starts with a
    await bulk(page).click();
    await expect(box(page, 'k0')).not.toBeChecked();
    await expect(bulk(page)).toHaveText('Marcar todos');

    await search(page, '');
    await page.locator('#entity-type-filter').selectOption('');
    await expect(rows(page)).toHaveCount(5);
    await expect(box(page, 'k0')).not.toBeChecked();
    for (const key of ['k1', 'k2', 'k3', 'k4']) await expect(box(page, key)).toBeChecked();
    // Some listed entity is checked, so the button offers to uncheck.
    await expect(bulk(page)).toHaveText('Desmarcar todos');
  });

  test('with no filters it unchecks everything and Marcar todos restores everything', async ({ page }) => {
    await open(page, ENTITIES);
    await bulk(page).click();
    for (const key of ['k0', 'k1', 'k2', 'k3', 'k4']) await expect(box(page, key)).not.toBeChecked();
    await expect(bulk(page)).toHaveText('Marcar todos');
    await box(page, 'k2').check();
    await expect(bulk(page)).toHaveText('Desmarcar todos');
    await box(page, 'k2').uncheck();
    await expect(bulk(page)).toHaveText('Marcar todos');
    await bulk(page).click();
    for (const key of ['k0', 'k1', 'k2', 'k3', 'k4']) await expect(box(page, key)).toBeChecked();
  });

  test('is disabled when the filtered list is empty', async ({ page }) => {
    await open(page, ENTITIES);
    await expect(bulk(page)).toBeEnabled();
    await search(page, 'no-such-entity');
    await expect(rows(page)).toHaveCount(0);
    await expect(bulk(page)).toBeDisabled();
    await search(page, '');
    await expect(bulk(page)).toBeEnabled();
  });
});

test.describe('S3 loading screen while anonymizing', () => {
  const ENTITIES = [{ value: 'Ana Lopez' }, { value: 'Beatriz Gil' }];

  test('the overlay shows while the server works and disappears on success', async ({ page }) => {
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    await open(page, ENTITIES, { apply: async route => { await gate; await route.fulfill({ json: applyOk }); } });
    await expect(page.locator('#apply-loading')).toBeHidden();

    await page.locator('#apply').click();
    await expect(page.locator('#apply-loading')).toBeVisible();
    await expect(page.locator('#apply-loading')).toContainText('Anonimusing');
    await expect(page.locator('#apply-loading')).toContainText('Anonimizando documento');
    await expect(page.locator('#apply')).toBeDisabled();

    release();
    await expect(page.locator('#result-status')).toContainText('Anonimización completada');
    await expect(page.locator('#apply-loading')).toBeHidden();
    await expect(page.locator('#apply')).toBeEnabled();
  });

  test('the overlay disappears when the server answers with an error', async ({ page }) => {
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    await open(page, ENTITIES, { apply: async route => {
      await gate;
      await route.fulfill({ status: 500, json: { error: 'fallo simulado' } });
    } });

    await page.locator('#apply').click();
    await expect(page.locator('#apply-loading')).toBeVisible();

    release();
    await expect(page.locator('#result-status')).toContainText('No se pudo completar la anonimización');
    await expect(page.locator('#apply-loading')).toBeHidden();
    await expect(page.locator('#apply')).toBeEnabled();
  });
});

test.describe('S4 locating from the document clears the filters', () => {
  const ENTITIES = [{ value: 'Ana Lopez' }, { value: 'Beatriz Gil' }, { value: '28001', type: 'CP' }];

  test('clicking a document mark resets text and type filters and activates its row', async ({ page }) => {
    await open(page, ENTITIES);
    await search(page, 'Ana');
    await page.locator('#entity-type-filter').selectOption('PERSONA');
    await expect(rows(page)).toHaveCount(1);
    await expect(page.locator('#entities [data-entity-key="k1"]')).toHaveCount(0);

    await page.locator('#doctext .hl').filter({ hasText: 'Beatriz Gil' }).click();

    await expect(page.locator('#entity-search')).toHaveValue('');
    await expect(page.locator('#entity-type-filter')).toHaveValue('');
    await expect(rows(page)).toHaveCount(3);
    await expect(page.locator('#entities [data-entity-key="k1"]')).toBeVisible();
    await expect(page.locator('#entities [data-entity-key="k1"]')).toHaveClass(/\bactive\b/);
  });

  test('a filter that hides every row is also cleared', async ({ page }) => {
    await open(page, ENTITIES);
    await page.locator('#entity-type-filter').selectOption('CP');
    await search(page, 'zzz');
    await expect(rows(page)).toHaveCount(0);

    await page.locator('#doctext .hl').filter({ hasText: '28001' }).click();

    await expect(page.locator('#entity-search')).toHaveValue('');
    await expect(page.locator('#entity-type-filter')).toHaveValue('');
    await expect(page.locator('#entities [data-entity-key="k2"]')).toHaveClass(/\bactive\b/);
  });
});

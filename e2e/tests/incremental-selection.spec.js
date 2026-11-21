const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');

// A review with many entities, so a checkbox toggle must patch its own row and ranges in place
// instead of rebuilding every entity row and document mark.
const COUNT = 40;
const names = Array.from({ length: COUNT }, (_, index) => 'Nombre' + index + ' Apellido');
const text = names.join(' | ');
const detections = names.map((name, index) => {
  const start = text.indexOf(name);
  return { id: 'd' + index, type: 'PERSONA', start, end: start + name.length, entityKey: 'k' + index,
    provenance: 'REGEX', confidence: 1 };
});
// The mocked server keeps rejected names in the result and replaces the rest with labels.
function applyResult(rejected) {
  const labels = names.map((_, index) => ({ entityKey: 'k' + index, label: '[P' + index + ']' }))
    .filter(entry => !rejected.has(entry.entityKey));
  const markdown = names.map((name, index) => rejected.has('k' + index) ? name : '[P' + index + ']').join(' | ');
  return { deliverable: true, markdown, substitutions: labels.length, entities: labels.length, labels, findings: [] };
}

const checkbox = key => `#entities [data-entity-key="${key}"] input[type=checkbox]`;

// Tags the current rows and document marks so later assertions compare element identity.
async function snapshotNodes(page) {
  await page.evaluate(() => {
    window.__rows = new Map([...document.querySelectorAll('#entities [data-entity-key]')]
      .map(row => [row.dataset.entityKey, row]));
    window.__marks = new Map([...document.querySelectorAll('#doctext .hl')]
      .map(mark => [mark.dataset.comparisonOccurrence, mark]));
  });
}

// Entity keys whose row is a new element, and occurrence ids whose mark is a new element,
// ignoring the toggled entity itself.
async function replacedNodes(page, toggledKey, toggledId) {
  return page.evaluate(([key, id]) => ({
    rows: [...document.querySelectorAll('#entities [data-entity-key]')]
      .filter(row => row.dataset.entityKey !== key && window.__rows.get(row.dataset.entityKey) !== row)
      .map(row => row.dataset.entityKey),
    marks: [...document.querySelectorAll('#doctext .hl')]
      .filter(mark => mark.dataset.comparisonOccurrence !== id
        && window.__marks.get(mark.dataset.comparisonOccurrence) !== mark)
      .map(mark => mark.dataset.comparisonOccurrence)
  }), [toggledKey, toggledId]);
}

async function focusIsOnTaggedCheckbox(page, key) {
  return page.evaluate(entityKey => {
    const original = window.__rows.get(entityKey).querySelector('input[type=checkbox]');
    return document.activeElement === original && original.isConnected;
  }, key);
}

test('keyboard checkbox toggles patch the review in place and keep comparison marks correct', async ({ page }) => {
  const { baseUrl } = JSON.parse(await fs.readFile(path.resolve(__dirname, '../.runtime/server.json'), 'utf8'));
  await page.route('**/api/analyze', route => route.fulfill({ json: {
    jobId: 'incremental', text, pageCount: 1, elapsedMs: 1, types: ['PERSONA'], detections
  } }));
  const rejected = new Set();
  await page.route('**/api/apply', route => route.fulfill({ json: applyResult(rejected) }));
  await page.setViewportSize({ width: 1920, height: 1080 });
  await page.goto(baseUrl);
  await page.locator('#file').setInputFiles({ name: 'incremental.pdf', mimeType: 'application/pdf',
    buffer: Buffer.from('fixture') });
  await expect(page.locator('#workspace')).toBeVisible();
  await expect(page.locator('#entities [data-entity-key]')).toHaveCount(COUNT);
  await expect(page.locator('#doctext .hl')).toHaveCount(COUNT);

  // Space on a focused checkbox rejects the entity; focus stays on the very same element.
  await snapshotNodes(page);
  await page.locator(checkbox('k5')).focus();
  await page.keyboard.press('Space');
  await expect(page.locator(checkbox('k5'))).not.toBeChecked();
  await expect(page.locator('#entities [data-entity-key="k5"]')).toHaveClass(/\boff\b/);
  await expect(page.locator('#entities [data-entity-key="k5"]')).toContainText('Se conserva en el texto');
  await expect(page.locator('#doctext [data-comparison-occurrence="d5"]')).toHaveCount(0);
  await expect(page.locator('#doctext')).toContainText(names[5]);
  expect(await focusIsOnTaggedCheckbox(page, 'k5')).toBe(true);
  expect(await replacedNodes(page, 'k5', 'd5')).toEqual({ rows: [], marks: [] });

  // Space again restores the mark, still without moving focus or replacing other nodes.
  await page.keyboard.press('Space');
  await expect(page.locator(checkbox('k5'))).toBeChecked();
  await expect(page.locator('#entities [data-entity-key="k5"]')).not.toContainText('Se conserva en el texto');
  await expect(page.locator('#doctext [data-comparison-occurrence="d5"]')).toHaveCount(1);
  expect(await focusIsOnTaggedCheckbox(page, 'k5')).toBe(true);
  expect(await replacedNodes(page, 'k5', 'd5')).toEqual({ rows: [], marks: [] });
  await page.locator('#doctext [data-comparison-occurrence="d5"]').press('Enter');
  await expect(page.locator('#entities [data-entity-key="k5"]')).toHaveClass(/\bactive\b/);

  // Rejecting a second entity by keyboard also patches in place.
  await snapshotNodes(page);
  await page.locator(checkbox('k3')).focus();
  await page.keyboard.press('Space');
  await expect(page.locator(checkbox('k3'))).not.toBeChecked();
  await expect(page.locator('#doctext [data-comparison-occurrence="d3"]')).toHaveCount(0);
  expect(await focusIsOnTaggedCheckbox(page, 'k3')).toBe(true);
  expect(await replacedNodes(page, 'k3', 'd3')).toEqual({ rows: [], marks: [] });

  // Comparison built after the in-place toggles links every replaced and preserved occurrence.
  rejected.add('k3');
  await page.locator('#apply').click();
  await expect(page.locator('#result-status')).toContainText('Anonimización completada');
  await page.locator('#compare').click();
  await expect(page.locator('#markdown .result-entity')).toHaveCount(COUNT);
  await expect(page.locator('#doctext .hl')).toHaveCount(COUNT);
  for (const id of ['d3', 'd5', 'd7']) {
    const source = page.locator(`#doctext [data-comparison-occurrence="${id}"]`);
    const result = page.locator(`#markdown [data-comparison-occurrence="${id}"]`);
    expect(await source.evaluate(element => getComputedStyle(element).color))
      .toBe(await result.evaluate(element => getComputedStyle(element).color));
    await source.hover();
    await expect(result).toHaveClass(/comparison-match/);
    await expect(page.locator('#markdown .comparison-match')).toHaveCount(1);
  }
  await expect(page.locator('#markdown [data-comparison-occurrence="d3"]')).toHaveText(names[3]);
  await page.locator('#compare').click();
  await expect(page.locator('#markdown .comparison-match')).toHaveCount(0);
});

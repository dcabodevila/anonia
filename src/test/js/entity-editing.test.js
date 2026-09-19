'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const app = require('../../main/resources/web/app.js');
const { state } = app;

beforeEach(() => {
  delete global.document;
  state.types = ['PERSONA', 'DIRECCION', 'EMAIL', 'CP'];
  state.edits = [];
  state.rejected = new Set();
  state.markdown = '';
  state.warningMarkdown = '';
  state.warningDownloadEligible = false;
  state.jobId = null;
  fixture('Maria  Garcia | Maria Garcia Lopez | Garcia', [
    ['a', 'PERSONA', 'Maria  Garcia', 'source'],
    ['b', 'PERSONA', 'Maria Garcia Lopez', 'source'],
    ['c', 'DIRECCION', 'Garcia', 'destination', 36]
  ]);
});
function fixture(text, specs) {
  state.text = text;
  const sourceGroups = new Map();
  state.detections = specs.map(([id, type, value, entityKey, from = 0]) => {
    const start = text.indexOf(value, from);
    assert.ok(start >= 0);
    const sourceValue = entityKey + '\u0000' + app.normalize(value);
    let reviewKey = sourceGroups.get(sourceValue);
    if (!reviewKey) {
      reviewKey = sourceGroups.has(entityKey) ? 'review:' + id : entityKey;
      sourceGroups.set(sourceValue, reviewKey);
      sourceGroups.set(entityKey, reviewKey);
    }
    return { id, type, start, end: start + value.length, entityKey: reviewKey };
  });
  app.groupEntities();
}
const detection = id => state.detections.find(d => d.id === id);
const key = id => detection(id).entityKey;
const effectiveIds = () => app.effectiveDetections().map(d => d.id);
const snapshot = () => JSON.stringify([state.detections, [...state.entities], [...state.rejected], state.edits]);

test('whitespace normalization maps exact contiguous source substrings to UTF16 offsets', () => {
  assert.deepEqual(app.narrow('Maria  Garcia', 'Garcia'), [7, 13]);
  assert.deepEqual(app.narrow('  Ana\n Ruiz', 'Ana Ruiz'), [2, 11]);
  assert.deepEqual(app.narrow('preAna Garcia', 'Ana'), [3, 6]);
  assert.equal(app.narrow('Maria Garcia Lopez', 'Maria Lopez'), null);
  assert.deepEqual(app.narrow('Rúa Estrada de Bueu, 96, Piñeiro,', 'Rúa Estrada de Bueu, 96, Piñeiro'), [0, 32]);
  assert.deepEqual(app.narrow('000/10812,', '000'), [0, 3]);
  assert.deepEqual(app.narrow('000/10812,', '10812'), [4, 9]);
  assert.deepEqual(app.narrow('A\n\u00a0B  ', 'A B'), [0, 4]);
  assert.deepEqual(app.narrow('😀 A', 'A'), [3, 4]);
});

test('code fragment expansion anchors the original occurrence without jumping', () => {
  const text = '000/10812, | 000/10812,';
  const second = text.indexOf('10812', 5);
  fixture(text, [
    ['a', 'CP', '10812', 'codes'], ['b', 'CP', '10812', 'codes', second]
  ]);
  assert.equal(app.changeText('codes', '000/10812'), true);
  assert.equal(state.text.slice(detection('a').start, detection('a').end), '000/10812');
  assert.equal(detection('a').start, 0);
  assert.equal(detection('b').start, text.lastIndexOf('000/10812'));
  assert.equal(state.text.slice(detection('b').start, detection('b').end), '000/10812');
  assert.equal(app.changeText('codes', '000/10812'), true);
  assert.equal(detection('a').start, 0);
  assert.equal(detection('b').start, text.lastIndexOf('000/10812'));
  assert.equal(state.text.slice(detection('b').start, detection('b').end), '000/10812');
  const elsewhere = '000/10812, | 10812,';
  const elsewhereSecond = elsewhere.indexOf('10812', 5);
  fixture(elsewhere, [['b', 'CP', '10812', 'code', elsewhereSecond]]);
  assert.equal(app.changeText('code', '000/10812'), false);
});

test('edits only the matching source value without merging variants, types or rejection', () => {
  state.rejected.add('destination');
  assert.equal(app.changeText('source', 'Garcia'), true);
  assert.equal(detection('a').end, 13);
  assert.equal(detection('b').start, 16);
  assert.equal(detection('b').end, 34);
  assert.equal(detection('b').type, 'PERSONA');
  assert.notEqual(key('b'), key('a'));
  assert.notEqual(key('b'), key('c'));
  assert.equal(state.rejected.has('destination'), true);
  assert.equal(state.rejected.has(key('b')), false);
  assert.equal(state.entities.get(key('b')).best, 'Maria Garcia Lopez');
  assert.equal(state.entities.get(key('b')).count, 1);
  assert.equal(app.reviewBody(), 'review-v1\ntext\ta\tGarcia');
  app.changeType(key('b'), 'EMAIL');
  assert.equal(detection('b').type, 'EMAIL');
  assert.equal(detection('a').type, 'PERSONA');
  assert.equal(app.changeType(key('b'), 'bogus'), false);
});

test('editing one source-value group leaves rejection of other variants unchanged', () => {
  state.rejected.add('source');
  assert.equal(app.changeText('source', 'Garcia'), true);
  assert.equal(state.rejected.has(key('b')), false);
  assert.equal(state.rejected.has(key('a')), true);
  assert.equal(state.rejected.has('destination'), false);
});

test('source-anchored expansion can include adjacent source text but rejects empty edits', () => {
  assert.equal(app.changeText('source', 'Maria  Garcia |'), true);
  assert.equal(app.changeText('source', ''), false);
});

test('Rua expansion retains nested state, UTF16 whitespace offsets and other variants', () => {
  const full = 'Rúa Estrada de Bueu, 96, Piñeiro, Aldán, Cangas do Morrazo';
  const text = '😀 ' + full.replace(', Piñeiro', ',\n\u00a0Piñeiro') + ' | Piñeiro | Outra rúa';
  fixture(text, [
    ['a', 'DIRECCION', 'Rúa Estrada de Bueu, 96', 'address'],
    ['b', 'PERSONA', 'Piñeiro', 'person'],
    ['c', 'PERSONA', 'Piñeiro', 'person', text.indexOf(' | ')],
    ['d', 'DIRECCION', 'Outra rúa', 'address']
  ]);
  assert.equal(app.changeText('address', full), true);
  const selection = key('a');
  assert.deepEqual(state.detections.map(d => d.id), ['a', 'b', 'c', 'd']);
  assert.equal(detection('a').start, 3);
  assert.equal(detection('a').end, text.indexOf(' | '));
  assert.deepEqual(effectiveIds(), ['a', 'c', 'd']);
  assert.deepEqual(state.entities.get('person').ids, ['b', 'c']);
  state.rejected.add(selection);
  assert.deepEqual(effectiveIds(), ['b', 'c', 'd']);
  state.rejected.delete(selection);
  assert.equal(app.changeText(selection, 'Bueu,'), true);
  assert.deepEqual(effectiveIds(), ['a', 'b', 'c', 'd']);
  assert.equal(app.changeText(selection, full), true);
  state.rejected.add('person');
  assert.deepEqual(effectiveIds(), ['a', 'd']);
  assert.equal(detection('b').type, 'PERSONA');
  assert.equal(state.rejected.has(selection), false);
  assert.equal(app.reviewBody(), 'review-v1\n' + [full, 'Bueu,', full]
    .map(value => 'text\ta\t' + encodeURIComponent(value)).join('\n'));
});

test('repeated numbers in same and different groups are not expanded elsewhere', () => {
  fixture('Rúa 96 portal | Rúa 96 portal | 96', [
    ['a', 'CP', '96', 'number'], ['b', 'CP', '96', 'number', 15], ['c', 'CP', '96', 'other', 29]
  ]);
  assert.equal(app.changeText('number', 'Rúa 96 portal'), true);
  assert.equal(state.text.slice(detection('b').start, detection('b').end), 'Rúa 96 portal');
  assert.equal(state.text.slice(detection('c').start, detection('c').end), '96');
  assert.deepEqual(effectiveIds(), ['a', 'b', 'c']);
  state.rejected.add(key('a'));
  assert.deepEqual(effectiveIds(), ['c']);
});

test('all exact source occurrences are counted and edited as one batch', () => {
  const code = 'TRA/2023/36/000/10812';
  fixture(code + ' | ' + code + ' | TRA/2023/36/000/108120', [
    ['a', 'CP', '10812', 'codes'],
    ['exact:a:44:49', 'CP', '10812', 'codes', 44]
  ]);

  assert.equal(state.entities.get('codes').count, 2);
  assert.equal(app.changeText('codes', code), true);
  assert.deepEqual(state.detections.map(d => state.text.slice(d.start, d.end)), [code, code]);
  assert.equal(state.detections.length, 2);
});

test('collapsed retained spans count as one visible occurrence and edited meta keeps count', () => {
  fixture('10812/10812', [
    ['a', 'CP', '10812', 'codes'], ['b', 'CP', '10812', 'codes', 6]
  ]);
  const { node, nodes } = fakeDom();
  const button = node();
  app.editInline(button, 'codes');
  button.replacement.value = '10812/10812';
  button.replacement.listeners.keydown({ key: 'Enter', preventDefault() {} });
  assert.deepEqual(state.detections.map(d => d.id), ['a', 'b']);
  assert.deepEqual(effectiveIds(), ['b']);

  const meta = nodes.get('entities').children[0].children[1].children[1].textContent;
  assert.deepEqual([state.entities.get('codes').count, meta], [
    1, 'Seleccion editada · posicion original 1 · 1 aparicion'
  ]);

  fixture('10812/10812 | 10812', [
    ['a', 'CP', '10812', 'codes'], ['b', 'CP', '10812', 'codes', 6],
    ['c', 'CP', '10812', 'codes', 14]
  ]);
  assert.equal(app.changeText('codes', '10812/10812'), true);
  assert.deepEqual(state.detections.map(d => [d.id, d.start, d.end]), [
    ['a', 0, 11], ['b', 0, 11], ['c', 14, 19]
  ]);
  assert.equal(state.entities.get('codes').count, 2);
});

test('generated selection identities cannot collide with existing groups', () => {
  fixture('96 | 96 | Garcia', [
    ['a', 'CP', '96', 'number'], ['b', 'CP', '96', 'number', 5],
    ['c', 'PERSONA', 'Garcia', 'review:a']
  ]);
  assert.equal(app.changeText('number', '96'), true);
  assert.equal(key('a'), 'number');
  app.changeType(key('a'), 'EMAIL');
  assert.equal(detection('b').type, 'EMAIL');
  assert.equal(detection('c').type, 'PERSONA');
});

test('partial overlap rejects atomically with actionable Spanish error', () => {
  fixture('Calle Norte sector final', [
    ['a', 'DIRECCION', 'Calle', 'one'], ['b', 'PERSONA', 'sector final', 'two']
  ]);
  const before = snapshot();
  assert.equal(app.changeText('one', 'Calle Norte sector'), false);
  assert.equal(snapshot(), before);
  assert.match(state.editError, /Selecciona|completa/);
});

test('immutable anchors support narrowing and re-expansion, never jumps or ambiguous matches', () => {
  fixture('Ana | Ana Ruiz | Ana', [['a', 'PERSONA', 'Ana Ruiz', 'one']]);
  assert.equal(app.changeText('one', 'Ana'), true);
  assert.equal(detection('a').start, 6);
  assert.equal(app.changeText('one', 'Ana Ruiz'), true);
  assert.equal(detection('a').end, 14);
  assert.equal(app.changeText('one', 'Ana |'), false);
  fixture('Ana Ana Ana', [['a', 'PERSONA', 'Ana', 'one', 4]]);
  assert.equal(app.changeText('one', 'Ana Ana'), false);
});

test('equal ranges retain identities and latest explicit edit wins one substitution', () => {
  fixture('Calle Norte | Calle Norte', [
    ['a', 'DIRECCION', 'Calle', 'one'], ['b', 'PERSONA', 'Norte', 'inner'],
    ['c', 'EMAIL', 'Calle Norte', 'two', 12]
  ]);
  state.rejected.add('two');
  assert.equal(app.changeText('one', 'Calle Norte'), true);
  assert.equal(app.changeText('inner', 'Calle Norte'), true);
  assert.deepEqual(state.detections.map(d => d.id), ['a', 'b', 'c']);
  assert.deepEqual(effectiveIds(), ['b']);
  assert.equal(detection('a').type, 'DIRECCION');
  assert.equal(detection('b').type, 'PERSONA');
  state.rejected.add('inner');
  assert.deepEqual(effectiveIds(), ['a']);
  state.rejected.delete('inner');
  assert.equal(app.changeText('one', 'Calle Norte'), true);
  assert.deepEqual(effectiveIds(), ['a']);
  fixture('Calle Norte', [['z', 'EMAIL', 'Calle Norte', 'z'], ['a', 'DIRECCION', 'Calle Norte', 'a']]);
  assert.deepEqual(effectiveIds(), ['z']);
});

test('contained tag remains editable without replacing accepted outer', () => {
  fixture('Calle Maria Garcia', [
    ['a', 'DIRECCION', 'Calle Maria Garcia', 'outer'], ['b', 'PERSONA', 'Maria Garcia', 'inner']
  ]);
  assert.equal(app.changeText('inner', 'Garcia'), true);
  assert.deepEqual(effectiveIds(), ['a']);
  state.rejected.add('outer');
  assert.deepEqual(effectiveIds(), ['b']);
  assert.equal(state.entities.get('inner').best, 'Garcia');
});

test('an unchosen conflicting variant cannot reject a local edit', () => {
  const text = 'Calle Norte sector | Calle Norte sector final';
  fixture(text, [
    ['a', 'DIRECCION', 'Calle', 'one'], ['b', 'DIRECCION', 'Calle', 'one', 20],
    ['c', 'PERSONA', 'sector final', 'two']
  ]);
  assert.equal(app.changeText('one', 'Calle Norte sector'), true);
  assert.equal(state.text.slice(detection('b').start, detection('b').end), 'Calle');
});

function fakeDom() {
  const nodes = new Map();
  function node() {
    const classes = new Set();
    return { classList: {
      add(...names) { names.forEach(name => classes.add(name)); },
      remove(...names) { names.forEach(name => classes.delete(name)); },
      toggle(name, force) {
        const enabled = force === undefined ? !classes.has(name) : force;
        if (enabled) classes.add(name); else classes.delete(name);
        return enabled;
      },
      contains(name) { return classes.has(name); }
    }, style: {}, dataset: {}, attributes: {}, children: [],
      listeners: {}, append(...children) { this.children.push(...children); },
      setAttribute(name, value) { this.attributes[name] = String(value); }, focus() {}, select() {}, click() {},
      replaceWith(other) { this.replacement = other; },
      addEventListener(name, fn) { this.listeners[name] = fn; },
      querySelector() { return node(); } };
  }
  global.document = {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    createElement: node, createTextNode: value => value, querySelectorAll: () => []
  };
  global.getComputedStyle = () => ({ getPropertyValue: () => '' });
  return { node, nodes };
}

test('Enter then blur saves displayed occurrence once; independent checkbox and Escape work', () => {
  const { node } = fakeDom();
  const button = node();
  app.editInline(button, 'source');
  assert.equal(button.replacement.value, 'Maria Garcia');
  const input = button.replacement;
  input.value = 'Maria';
  input.listeners.keydown({ key: 'Enter', preventDefault() {} });
  input.listeners.blur();
  assert.deepEqual(state.edits, [['text', 'a', 'Maria']]);
  const rows = document.getElementById('entities').children;
  const selectedRow = rows.find(row => row.children[1].children[0].textContent === 'Maria');
  assert.ok(selectedRow);
  assert.match(selectedRow.children[1].children[1].textContent, /Seleccion editada.*1/);
  selectedRow.children[0].listeners.change();
  assert.deepEqual(state.edits[1], ['reject', 'a', 'true']);
  assert.equal(state.rejected.has(key('b')), false);
  const cancel = node();
  app.editInline(cancel, key('a'));
  cancel.replacement.value = 'invalid';
  cancel.replacement.listeners.keydown({ key: 'Escape', preventDefault() {} });
  cancel.replacement.listeners.blur();
  assert.equal(state.edits.length, 2);
});

test('blur saves and invalid edits leave review intact', () => {
  const { node } = fakeDom();
  const button = node();
  app.editInline(button, 'source');
  button.replacement.value = 'Maria';
  button.replacement.listeners.blur();
  assert.equal(state.edits.length, 1);
  const bad = node();
  app.editInline(bad, key('b'));
  bad.replacement.value = '';
  bad.replacement.listeners.blur();
  assert.equal(state.edits.length, 1);
});

test('rendering projects accepted outer once and reveals inner when rejected', () => {
  fakeDom();
  fixture('Calle Maria Garcia', [
    ['a', 'DIRECCION', 'Calle Maria Garcia', 'outer'], ['b', 'PERSONA', 'Maria Garcia', 'inner']
  ]);
  app.renderDocument();
  const container = document.getElementById('doctext');
  assert.deepEqual(container.children.filter(c => typeof c !== 'string').map(c => c.textContent), ['Calle Maria Garcia']);
  container.children = [];
  state.rejected.add('outer');
  app.renderDocument();
  assert.deepEqual(container.children.filter(c => typeof c !== 'string').map(c => c.textContent), ['Maria Garcia']);
  assert.equal(container.children.map(c => typeof c === 'string' ? c : c.textContent).join(''), state.text);
});

test('manual creation finds all eligible occurrences once and produces a CODIGO review operation', () => {
  fixture('REF-1 | REF-1 | REF-12', []);

  assert.equal(app.addManualEntity('REF-1'), true);
  assert.deepEqual(state.detections.map(detection => [detection.id, detection.type, detection.start, detection.end]), [
    ['manual:1', 'CODIGO', 0, 5], ['manual:1:8:13', 'CODIGO', 8, 13]
  ]);
  assert.equal(state.entities.get('manual:1').count, 2);
  assert.equal(app.reviewBody(), 'review-v1\nadd\tmanual%3A1\tREF-1');
  assert.equal(app.changeText('manual:1', 'REF'), true);
  assert.deepEqual(state.detections.map(detection => state.text.slice(detection.start, detection.end)), ['REF', 'REF']);

  assert.equal(app.addManualEntity('REF-1'), true);
  assert.equal(state.detections.length, 2);
  assert.equal(state.edits.length, 2);
});

test('manual creation rejects partial overlaps but accepts contained ranges', () => {
  fixture('ABCDE', []);

  assert.equal(app.addManualEntity('ABC'), true);
  assert.equal(app.addManualEntity('CDE'), false);
  assert.deepEqual(state.detections.map(detection => [detection.id, detection.start, detection.end]), [
    ['manual:1', 0, 3]
  ]);
  assert.equal(app.addManualEntity('ABCDE'), true);
  assert.deepEqual(state.detections.map(detection => [detection.id, detection.start, detection.end]), [
    ['manual:1', 0, 3], ['manual:2', 0, 5]
  ]);
});

test('manual creation uses the same Unicode digit boundary as server exact occurrences', () => {
  fixture('𝟙REF-1 | REF-1', []);

  assert.equal(app.addManualEntity('REF-1'), true);
  assert.deepEqual(state.detections.map(detection => [detection.start, detection.end]), [[10, 15]]);
});

test('manual draft defaults to CODIGO, commits once on Enter then blur, and reports no source match', () => {
  const { nodes } = fakeDom();
  fixture('REF-1 | REF-1', []);

  app.startManualEntity();
  const draft = nodes.get('entities').children.at(-1);
  const input = draft.children[0].children[0];
  assert.equal(draft.children[1].textContent, 'CODIGO');
  input.value = 'REF-1';
  input.listeners.keydown({ key: 'Enter', preventDefault() {} });
  input.listeners.blur();
  assert.equal(state.edits.length, 1);
  assert.equal(state.detections.length, 2);

  app.startManualEntity();
  const unmatched = nodes.get('entities').children.at(-1).children[0].children[0];
  unmatched.value = 'AUSENTE';
  unmatched.listeners.blur();
  assert.equal(document.getElementById('error').textContent, 'Ningún elemento encontrado');
  assert.equal(state.detections.length, 2);
});

test('backend-provided CODIGO is a selectable manual classification', async () => {
  const { nodes } = fakeDom();
  global.fetch = async () => ({ ok: true, json: async () => ({
    jobId: 'code-review', text: 'Maria Garcia', pageCount: 1, elapsedMs: 1,
    types: ['PERSONA', 'CODIGO'],
    detections: [{ id: 'person-1', type: 'PERSONA', start: 0, end: 12, entityKey: 'person' }]
  }) });

  await app.analyze('document');

  const chip = nodes.get('entities').children[0].children[2];
  assert.deepEqual(chip.children.map(option => option.value), ['PERSONA', 'CODIGO']);
  chip.value = 'CODIGO';
  chip.listeners.change();
  assert.equal(detection('person-1').type, 'CODIGO');
  assert.equal(app.reviewBody(), 'review-v1\ntype\tperson-1\tCODIGO');
});

test('older analysis responses cannot overwrite latest upload state', async () => {
  fakeDom();
  const responses = [];
  global.fetch = () => new Promise(resolve => responses.push(resolve));
  const older = app.analyze('old file');
  const newer = app.analyze('new file');
  responses[1]({ ok: false, json: async () => ({ error: 'latest error' }) });
  await newer;
  responses[0]({ ok: true, json: async () => ({ jobId: 'stale', detections: [] }) });
  await older;
  assert.equal(state.jobId, null);
  assert.equal(document.getElementById('error').textContent, 'latest error');
});

test('a successful blocked result enables only the explicit warning download', async () => {
  fakeDom();
  state.jobId = 'job';
  global.fetch = async () => ({ ok: true, json: async () => ({
    deliverable: false, markdown: 'resultado con datos residuales', substitutions: 1, entities: 1,
    findings: [{ control: 'C3', severity: 'BLOCKING', detail: 'Dato residual' }]
  }) });

  await app.apply();

  assert.equal(state.warningMarkdown, 'resultado con datos residuales');
  assert.equal(document.getElementById('download').disabled, true);
  assert.equal(document.getElementById('copy').disabled, true);
  assert.equal(document.getElementById('warning-download').disabled, false);
  assert.equal(app.downloadWithWarnings(), true);
});

test('warning export stays unavailable for empty and failed apply responses', async () => {
  fakeDom();
  state.jobId = 'job';
  state.warningMarkdown = 'old';
  state.warningDownloadEligible = true;
  global.fetch = async () => ({ ok: true, json: async () => ({
    deliverable: false, markdown: '   ', substitutions: 0, entities: 0, findings: []
  }) });
  await app.apply();
  assert.equal(state.warningDownloadEligible, false);
  assert.equal(app.downloadWithWarnings(), false);

  state.warningMarkdown = 'old';
  state.warningDownloadEligible = true;
  global.fetch = async () => ({ ok: false, json: async () => ({ error: 'Revision no valida' }) });
  await app.apply();
  assert.equal(state.warningDownloadEligible, false);
  assert.equal(document.getElementById('warning-download').disabled, true);
});

test('review changes clear downloads and discard in-flight apply response', async () => {
  fakeDom();
  state.jobId = 'job';
  state.markdown = 'old';
  let resolve;
  global.fetch = () => new Promise(done => { resolve = done; });
  const pending = app.apply();
  app.invalidateResult();
  assert.equal(state.markdown, '');
  assert.equal(document.getElementById('download').disabled, true);
  resolve({ ok: true, json: async () => ({ deliverable: true, markdown: 'stale' }) });
  await pending;
  assert.equal(state.markdown, '');
  assert.equal(document.getElementById('apply').disabled, false);
});

test('comparison preserves its view through review invalidation, while tab selection exits it', () => {
  fakeDom();
  state.activeTab = 'document';
  state.comparing = false;

  app.toggleCompare();
  assert.equal(state.comparing, true);
  app.invalidateResult();
  assert.equal(state.comparing, true);

  app.selectTab('result');
  assert.equal(state.activeTab, 'result');
  assert.equal(state.comparing, false);

  app.toggleCompare();
  app.toggleCompare();
  assert.equal(state.activeTab, 'result');
  assert.equal(state.comparing, false);
});

test('comparison collapses only entity controls while result actions remain available', () => {
  const { nodes } = comparisonDom();
  state.activeTab = 'document';
  state.comparing = false;

  app.toggleCompare();

  assert.equal(state.comparing, true);
  assert.equal(nodes.get('workspace').classList.contains('comparing'), true);
  assert.equal(nodes.get('pane-side').classList.contains('comparing'), true);
  assert.equal(nodes.get('entity-controls').hidden, true);
  assert.equal(nodes.get('entity-controls').inert, true);
  assert.equal(nodes.get('entity-controls').attributes['aria-hidden'], 'true');
  assert.equal(nodes.get('result-actions').hidden, undefined);
  assert.equal(nodes.get('result-actions').inert, undefined);
  assert.equal(nodes.get('result-actions').attributes['aria-hidden'], undefined);
  assert.equal(nodes.get('compare').attributes['aria-pressed'], 'true');
  assert.equal(nodes.get('compare-label').textContent, 'Salir de comparación');

  app.toggleCompare();

  assert.equal(state.comparing, false);
  assert.equal(nodes.get('workspace').classList.contains('comparing'), false);
  assert.equal(nodes.get('pane-side').classList.contains('comparing'), false);
  assert.equal(nodes.get('entity-controls').hidden, false);
  assert.equal(nodes.get('entity-controls').inert, false);
  assert.equal(nodes.get('entity-controls').attributes['aria-hidden'], 'false');
  assert.equal(nodes.get('result-actions').hidden, undefined);
  assert.equal(nodes.get('result-actions').inert, undefined);
  assert.equal(nodes.get('result-actions').attributes['aria-hidden'], undefined);
  assert.equal(nodes.get('compare').attributes['aria-pressed'], 'false');
  assert.equal(nodes.get('compare-label').textContent, 'Comparar');
});

function comparisonDom() {
  const { node, nodes } = fakeDom();
  ['workspace', 'pane-side', 'entity-controls', 'result-actions', 'compare', 'compare-label'].forEach(id =>
    document.getElementById(id));
  const documentTab = node();
  documentTab.dataset.tab = 'document';
  const resultTab = node();
  resultTab.dataset.tab = 'result';
  const documentPanel = node();
  documentPanel.id = 'tab-document';
  const resultPanel = node();
  resultPanel.id = 'tab-result';
  const previousQuerySelectorAll = document.querySelectorAll;
  document.querySelectorAll = selector => {
    if (selector === '.tab') return [documentTab, resultTab];
    if (selector === '.main-tabpanels > [role="tabpanel"]') return [documentPanel, resultPanel];
    return previousQuerySelectorAll(selector);
  };
  return { nodes };
}

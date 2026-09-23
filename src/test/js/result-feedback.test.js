'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const app = require('../../main/resources/web/app.js');
const { state } = app;

function fakeDom() {
  const nodes = new Map();
  const node = () => {
    const classes = new Set();
    return {
      textContent: '', innerHTML: '', disabled: false, dataset: {}, attributes: {}, style: {}, children: [], listeners: {},
      classList: {
        add(...names) { names.forEach(name => classes.add(name)); },
        remove(...names) { names.forEach(name => classes.delete(name)); },
        toggle(name, force) {
          const enabled = force === undefined ? !classes.has(name) : force;
          if (enabled) classes.add(name); else classes.delete(name);
          return enabled;
        },
        contains(name) { return classes.has(name); }
      },
      append(...items) { this.children.push(...items); },
      setAttribute(name, value) { this.attributes[name] = String(value); },
      addEventListener(name, listener) { this.listeners[name] = listener; },
      click() {}, focus() {}
    };
  };
  nodes.createdElements = [];
  global.document = {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    createElement(tagName) {
      const element = node();
      element.tagName = tagName;
      nodes.createdElements.push(element);
      return element;
    },
    createTextNode: value => value,
    querySelectorAll: () => []
  };
  return nodes;
}

beforeEach(() => {
  delete global.document;
  delete global.navigator;
  state.jobId = null;
  state.markdown = '';
  state.warningMarkdown = '';
  state.warningDownloadEligible = false;
  state.sourceFilename = null;
  state.resultStatus = 'pending';
  state.resultFindings = [];
  state.revision = 0;
  state.applyRequest = 0;
  state.copyRequest = 0;
  state.editError = null;
});

test('blocked findings still display the partial anonymized text and keep warning download available', async () => {
  const nodes = fakeDom();
  state.jobId = 'job';
  global.fetch = async () => ({ ok: true, json: async () => ({
    deliverable: false, markdown: '# Resultado parcial\n\nTexto protegido', substitutions: 1, entities: 1,
    findings: [{ control: 'C3', severity: 'BLOCKING', detail: 'Dato residual' }]
  }) });

  await app.apply();

  assert.equal(state.resultStatus, 'blocked');
  assert.deepEqual(state.resultFindings, [{ control: 'C3', severity: 'BLOCKING', detail: 'Dato residual' }]);
  assert.equal(nodes.get('markdown').textContent, '# Resultado parcial\n\nTexto protegido');
  assert.match(nodes.get('result-status').textContent, /C3: Dato residual/);
  assert.equal(nodes.get('warning-download').disabled, false);
  assert.equal(nodes.get('download').disabled, true);
  assert.equal(app.downloadWithWarnings(), true);
});

test('blocked preview retains risk note and plain copy label without enabling safe download', async () => {
  const nodes = fakeDom();
  state.jobId = 'job';
  global.fetch = async () => ({ ok: true, json: async () => ({
    deliverable: false, markdown: '# Resultado parcial', findings: [{ control: 'C3', detail: 'Riesgo' }]
  }) });
  await app.apply();
  assert.equal(nodes.get('download').disabled, true);
  assert.equal(nodes.get('copy').disabled, false);
  assert.equal(nodes.get('copy-label').textContent, 'Copiar');
  assert.match(nodes.get('warning-note').textContent, /copiar/i);
  assert.match(nodes.get('warning-note').textContent, /riesgo|datos personales/i);
  let copied;
  global.navigator = { clipboard: { writeText: async value => { copied = value; } } };
  await app.copyMarkdown();
  assert.equal(copied, '# Resultado parcial');
  assert.equal(nodes.get('copy-label').textContent, 'Copiado');
});

test('analyzing a file retains its source filename for later downloads', async () => {
  fakeDom();
  global.fetch = async () => ({ ok: true, json: async () => ({
    jobId: 'job', text: '', detections: [], types: [], pageCount: 1, elapsedMs: 1
  }) });

  await app.analyze({ name: 'contrato.pdf' });

  assert.equal(state.sourceFilename, 'contrato.pdf');
});

test('Markdown downloads use the source stem for safe and warning results', (t) => {
  const nodes = fakeDom();
  state.markdown = '# Safe result';
  state.warningMarkdown = '# Warning result';
  state.warningDownloadEligible = true;
  const originalBlob = global.Blob;
  const originalUrl = global.URL;
  global.Blob = class {};
  global.URL = { createObjectURL: () => 'blob:test', revokeObjectURL() {} };
  t.after(() => { global.Blob = originalBlob; global.URL = originalUrl; });

  state.sourceFilename = 'contrato.pdf';
  assert.equal(app.download(), true);
  assert.equal(app.downloadWithWarnings(), true);
  assert.deepEqual(nodes.createdElements.filter(element => element.tagName === 'a')
    .map(element => element.download), ['contrato-anonimused.md', 'contrato-anonimused.md']);

  state.sourceFilename = 'informe.final.pdf';
  assert.equal(app.download(), true);
  assert.equal(nodes.createdElements.at(-1).download, 'informe.final-anonimused.md');

  state.sourceFilename = '';
  assert.equal(app.download(), true);
  assert.equal(nodes.createdElements.at(-1).download, 'documento-anonimused.md');

  state.sourceFilename = 'unsafe/path.pdf';
  assert.equal(app.download(), true);
  assert.equal(nodes.createdElements.at(-1).download, 'documento-anonimused.md');
});

test('server failures show a friendly message and are logged for diagnosis', async () => {
  const nodes = fakeDom();
  state.jobId = 'job';
  const errors = [];
  const originalError = console.error;
  console.error = (...args) => errors.push(args);
  try {
    global.fetch = async () => ({ ok: false, json: async () => ({ error: 'NullPointerException: internal details' }) });
    await app.apply();
  } finally {
    console.error = originalError;
  }

  assert.match(nodes.get('result-status').textContent, /No se pudo completar la anonimización/);
  assert.doesNotMatch(nodes.get('result-status').textContent, /NullPointerException/);
  assert.equal(errors.length, 1);
  assert.match(String(errors[0][1]), /NullPointerException/);
});

test('a successful relevant retry replaces only its result error', async () => {
  fakeDom();
  state.jobId = 'job';
  state.editError = 'La edición local sigue requiriendo atención.';
  global.fetch = async () => ({ ok: false, json: async () => ({ error: 'Revisión no válida' }) });
  await app.apply();
  assert.equal(state.resultStatus, 'error');

  global.fetch = async () => ({ ok: true, json: async () => ({
    deliverable: true, markdown: '# Seguro', substitutions: 1, entities: 1, findings: []
  }) });
  await app.apply();

  assert.equal(state.resultStatus, 'success');
  assert.equal(state.editError, 'La edición local sigue requiriendo atención.');
  assert.equal(state.markdown, '# Seguro');
});

test('review invalidation clears output and findings, and a stale apply cannot restore them', async () => {
  fakeDom();
  state.jobId = 'job';
  state.markdown = '# Old result';
  state.warningMarkdown = 'unsafe old result';
  state.warningDownloadEligible = true;
  state.resultStatus = 'blocked';
  state.resultFindings = [{ control: 'C3', detail: 'Old finding' }];
  let resolve;
  global.fetch = () => new Promise(done => { resolve = done; });
  const pending = app.apply();
  app.invalidateResult();
  resolve({ ok: true, json: async () => ({
    deliverable: false, markdown: 'unsafe', findings: [{ control: 'C3', detail: 'stale' }]
  }) });
  await pending;

  assert.equal(state.resultStatus, 'pending');
  assert.deepEqual(state.resultFindings, []);
  assert.equal(state.markdown, '');
  assert.equal(state.warningMarkdown, '');
});

test('starting a new document clears the prior result before its analysis responds', async () => {
  fakeDom();
  state.markdown = '# Old result';
  state.resultStatus = 'success';
  state.resultFindings = [{ control: 'C3', detail: 'Old finding' }];
  let resolve;
  global.fetch = () => new Promise(done => { resolve = done; });

  const upload = app.analyze('new document');

  assert.equal(state.resultStatus, 'pending');
  assert.deepEqual(state.resultFindings, []);
  assert.equal(state.markdown, '');
  resolve({ ok: false, json: async () => ({ error: 'latest error' }) });
  await upload;
});

test('clipboard failure keeps the result and copy control available', async () => {
  const nodes = fakeDom();
  state.markdown = '# Resultado seguro';
  state.resultStatus = 'success';
  document.getElementById('copy-label').textContent = 'Copiar';
  global.navigator = { clipboard: { writeText: async () => { throw new Error('denied'); } } };

  await app.copyMarkdown();

  assert.equal(state.markdown, '# Resultado seguro');
  assert.equal(document.getElementById('copy').disabled, false);
  assert.equal(document.getElementById('copy-label').textContent, 'Copiar');
  assert.equal(state.resultStatus, 'error');
  assert.match(document.getElementById('result-status').textContent, /portapapeles/);
});

test('late clipboard rejection cannot replace newer blocked feedback or findings', async () => {
  fakeDom();
  state.jobId = 'job';
  state.markdown = '# Resultado anterior';
  let rejectCopy;
  global.navigator = { clipboard: { writeText: () => new Promise((resolve, reject) => { rejectCopy = reject; }) } };

  const copying = app.copyMarkdown();
  global.fetch = async () => ({ ok: true, json: async () => ({
    deliverable: false, markdown: '', substitutions: 0, entities: 0,
    findings: [{ control: 'C9', severity: 'BLOCKING', detail: 'Resultado más reciente' }]
  }) });
  await app.apply();
  rejectCopy(new Error('denied'));
  await copying;

  assert.equal(state.resultStatus, 'blocked');
  assert.deepEqual(state.resultFindings, [{ control: 'C9', severity: 'BLOCKING', detail: 'Resultado más reciente' }]);
});

test('late clipboard success and its reset cannot clobber current copy feedback', async (t) => {
  const nodes = fakeDom();
  state.markdown = '# Resultado seguro';
  document.getElementById('copy-label').textContent = 'Copiar';
  let resolveFirst;
  global.navigator = { clipboard: { writeText: () => new Promise(resolve => { resolveFirst = resolve; }) } };
  const first = app.copyMarkdown();

  app.invalidateResult();
  resolveFirst();
  await first;

  assert.equal(state.resultStatus, 'pending');
  assert.equal(nodes.get('copy-label').textContent, 'Copiar');

  const timers = [];
  const setTimeoutBeforeTest = global.setTimeout;
  global.setTimeout = callback => { timers.push(callback); };
  t.after(() => { global.setTimeout = setTimeoutBeforeTest; });
  global.navigator.clipboard.writeText = async () => {};
  state.markdown = '# Resultado actual';
  const second = app.copyMarkdown();
  await second;
  const third = app.copyMarkdown();
  await third;

  assert.equal(nodes.get('copy-label').textContent, 'Copiado');
  timers[0]();
  assert.equal(nodes.get('copy-label').textContent, 'Copiado');
  timers[1]();
  assert.equal(nodes.get('copy-label').textContent, 'Copiar');
});

test('blocked findings are shown exactly once in the result status', async () => {
  const nodes = fakeDom();
  state.jobId = 'job';
  global.fetch = async () => ({ ok: true, json: async () => ({
    deliverable: false, markdown: '', substitutions: 0, entities: 0,
    findings: [{ control: 'C3', severity: 'BLOCKING', detail: 'Dato residual' }]
  }) });

  await app.apply();

  const status = nodes.get('result-status').textContent;
  assert.equal(status.split('C3: Dato residual').length - 1, 1);
});

test('preview hides only the known leading generated metadata while export and clipboard keep exact bytes', async (t) => {
  const generated = '<!--\n'
    + 'doc-anonymizer 0.3.0\n'
    + 'generado: 2025-01-02T03:04:05Z\n'
    + 'origen sha-256: abcdef0123456789\n'
    + 'paginas: 2\n'
    + 'sustituciones: PERSONA=1\n\n'
    + 'DOCUMENTO DESIDENTIFICADO, NO ANONIMO. Se han sustituido identificadores\n'
    + 'directos detectados. Fechas, importes, cargos, localidades y hechos\n'
    + 'singulares permanecen y pueden permitir reidentificar. Requiere revision\n'
    + 'humana antes de compartir.\n'
    + '-->\n';
  const raw = generated + '## Resultado\n\nTexto protegido';
  const withSeparator = generated + '\n\n## Resultado\n\nTexto protegido';
  const ordinary = '<!-- comentario del usuario -->\n## Resultado';
  const unrecognized = '<!--\ndoc-anonymizer nota manual\n-->\n\n## Resultado';

  assert.equal(app.previewMarkdown(raw), '## Resultado\n\nTexto protegido');
  assert.equal(app.previewMarkdown(withSeparator), '## Resultado\n\nTexto protegido');
  assert.equal(app.previewMarkdown(ordinary), ordinary);
  assert.equal(app.previewMarkdown(unrecognized), unrecognized);
  assert.equal(app.previewMarkdown('Antes\n' + generated), 'Antes\n' + generated);

  fakeDom();
  state.markdown = raw;
  state.warningMarkdown = raw;
  assert.equal(state.warningMarkdown, raw);
  let copied = null;
  global.navigator = { clipboard: { writeText: async value => { copied = value; } } };
  await app.copyMarkdown();
  assert.equal(copied, raw);

  const blobs = [];
  const originalBlob = global.Blob;
  const originalUrl = global.URL;
  global.Blob = class { constructor(parts) { blobs.push(parts); } };
  global.URL = { createObjectURL: () => 'blob:test', revokeObjectURL() {} };
  t.after(() => { global.Blob = originalBlob; global.URL = originalUrl; });
  assert.equal(app.download(), true);
  assert.deepEqual(blobs, [[raw]]);
});

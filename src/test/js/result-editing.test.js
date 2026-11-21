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
  global.document = {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    createElement(tagName) { const element = node(); element.tagName = tagName; return element; },
    createTextNode: value => value,
    querySelectorAll: () => [],
    documentElement: {}
  };
  global.getComputedStyle = () => ({ getPropertyValue: () => '' });
  return nodes;
}

const METADATA = '<!--\ndoc-anonymizer 1.0\ngenerado: 2026-10-15\norigen sha-256: 0123456789abcdef\npaginas: 1\n'
  + 'sustituciones: 1\n\nDOCUMENTO DESIDENTIFICADO, NO ANONIMO. Se han sustituido identificadores\n'
  + 'directos detectados. Fechas, importes, cargos, localidades y hechos\n'
  + 'singulares permanecen y pueden permitir reidentificar. Requiere revision\n'
  + 'humana antes de compartir.\n-->\n\n';

function respond(body) {
  const calls = [];
  global.fetch = async (...args) => { calls.push(args); return { ok: true, json: async () => body }; };
  return calls;
}

function typeInResult(nodes, text) {
  nodes.get('markdown').innerText = text;
  app.editResult();
}

beforeEach(() => {
  delete global.document;
  delete global.navigator;
  delete global.confirm;
  state.jobId = 'job';
  state.text = '';
  state.detections = [];
  state.entities = new Map();
  state.rejected = new Set();
  state.edits = [];
  state.markdown = '';
  state.warningMarkdown = '';
  state.warningDownloadEligible = false;
  state.resultEdited = false;
  state.resultFindings = [];
  state.revision = 0;
  state.applyRequest = 0;
  state.copyRequest = 0;
});

test('result becomes editable only while a result exists', async () => {
  const nodes = fakeDom();
  respond({ deliverable: true, markdown: 'safe', substitutions: 1, entities: 1 });
  await app.apply();
  assert.equal(nodes.get('markdown').contentEditable, 'plaintext-only');
  assert.equal(nodes.get('markdown').ariaReadOnly, 'false');
  app.invalidateResult();
  assert.equal(nodes.get('markdown').contentEditable, 'false');
  assert.equal(nodes.get('markdown').ariaReadOnly, 'true');
});

test('blocked result without markdown is not editable', async () => {
  const nodes = fakeDom();
  respond({ deliverable: false, markdown: '', findings: [{ detail: 'Bloqueado' }] });
  await app.apply();
  assert.equal(nodes.get('markdown').contentEditable, 'false');
});

test('editing keeps the hidden metadata comment and copy uses the edited text', async () => {
  const nodes = fakeDom();
  respond({ deliverable: true, markdown: METADATA + 'Hola [PERSONA_1]', substitutions: 1, entities: 1 });
  await app.apply();
  typeInResult(nodes, 'Hola [PERSONA_1], editado');
  assert.equal(state.markdown, METADATA + 'Hola [PERSONA_1], editado');
  assert.equal(state.resultEdited, true);
  let copied = null;
  global.navigator = { clipboard: { writeText: async value => { copied = value; } } };
  await app.copyMarkdown();
  assert.equal(copied, METADATA + 'Hola [PERSONA_1], editado');
});

test('repeated edits never duplicate blank lines after the metadata comment', async () => {
  const nodes = fakeDom();
  respond({ deliverable: true, markdown: METADATA + '\n\nCuerpo', substitutions: 1, entities: 1 });
  await app.apply();
  typeInResult(nodes, '\nCuerpo 1');
  typeInResult(nodes, '\nCuerpo 2');
  assert.equal(state.markdown, METADATA + '\n\n\nCuerpo 2');
});

test('editing a warning result updates only the warning markdown', async () => {
  const nodes = fakeDom();
  respond({ deliverable: false, markdown: 'residual', findings: [{ detail: 'Residual' }] });
  await app.apply();
  typeInResult(nodes, 'corregido');
  assert.equal(state.warningMarkdown, 'corregido');
  assert.equal(state.markdown, '');
});

test('re-anonymizing an edited result asks first and keeps edits when cancelled', async () => {
  const nodes = fakeDom();
  respond({ deliverable: true, markdown: 'safe', substitutions: 1, entities: 1 });
  await app.apply();
  typeInResult(nodes, 'safe editado');
  const prompts = [];
  global.confirm = message => { prompts.push(message); return false; };
  const calls = respond({ deliverable: true, markdown: 'nuevo', substitutions: 1, entities: 1 });
  await app.apply();
  assert.equal(prompts.length, 1);
  assert.match(prompts[0], /ediciones/);
  assert.equal(calls.length, 0);
  assert.equal(state.markdown, 'safe editado');
  assert.equal(state.resultEdited, true);

  global.confirm = () => true;
  await app.apply();
  assert.equal(calls.length, 1);
  assert.equal(state.markdown, 'nuevo');
  assert.equal(state.resultEdited, false);
});

test('re-anonymizing an unedited result does not ask', async () => {
  fakeDom();
  respond({ deliverable: true, markdown: 'safe', substitutions: 1, entities: 1 });
  await app.apply();
  let asked = false;
  global.confirm = () => { asked = true; return false; };
  const calls = respond({ deliverable: true, markdown: 'otra vez', substitutions: 1, entities: 1 });
  await app.apply();
  assert.equal(asked, false);
  assert.equal(calls.length, 1);
  assert.equal(state.markdown, 'otra vez');
});

function seedDni() {
  state.text = 'Firma Juan con DNI 12345678Z.';
  state.detections = [
    { id: 'd1', type: 'DNI', start: 19, end: 28, entityKey: 'dni' },
    { id: 'p1', type: 'PERSONA', start: 6, end: 10, entityKey: 'juan' }
  ];
}

test('an edit that brings back an anonymized value blocks download and copy', async () => {
  const nodes = fakeDom();
  seedDni();
  respond({ deliverable: true, markdown: METADATA + 'Firma [PERSONA_1] con DNI [DNI_1].', substitutions: 2, entities: 2 });
  await app.apply();
  assert.equal(nodes.get('download').disabled, false);

  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678z.');

  assert.equal(nodes.get('download').disabled, true);
  assert.equal(nodes.get('copy').disabled, true);
  assert.equal(document.getElementById('edit-leak-note').classList.contains('hidden'), false);
  assert.match(document.getElementById('edit-leak-note').textContent, /dato anonimizado/);
  assert.doesNotMatch(document.getElementById('edit-leak-note').textContent, /12345678/);
  let saved = false;
  global.URL = { createObjectURL: () => { saved = true; return 'blob:'; }, revokeObjectURL() {} };
  assert.equal(app.download(), false);
  assert.equal(saved, false);
  let copied = null;
  global.navigator = { clipboard: { writeText: async value => { copied = value; } } };
  await app.copyMarkdown();
  assert.equal(copied, null);
});

test('removing the reintroduced value restores download and copy', async () => {
  const nodes = fakeDom();
  seedDni();
  respond({ deliverable: true, markdown: METADATA + 'Firma [PERSONA_1] con DNI [DNI_1].', substitutions: 2, entities: 2 });
  await app.apply();
  typeInResult(nodes, 'Firma JUAN con DNI [DNI_1].');
  assert.equal(nodes.get('download').disabled, true);

  typeInResult(nodes, 'Firma [PERSONA_1] con DNI [DNI_1], revisado.');

  assert.equal(nodes.get('download').disabled, false);
  assert.equal(nodes.get('copy').disabled, false);
  assert.equal(document.getElementById('edit-leak-note').classList.contains('hidden'), true);
});

test('a value the user chose to keep may stay in the edited result', async () => {
  const nodes = fakeDom();
  seedDni();
  state.rejected = new Set(['juan']);
  respond({ deliverable: true, markdown: METADATA + 'Firma Juan con DNI [DNI_1].', substitutions: 1, entities: 1 });
  await app.apply();

  typeInResult(nodes, 'Firma Juan con DNI [DNI_1], revisado.');

  assert.equal(nodes.get('download').disabled, false);
  assert.equal(document.getElementById('edit-leak-note').classList.contains('hidden'), true);
});


async function applyBlocked(nodes) {
  seedDni();
  respond({ deliverable: false, markdown: METADATA + 'Firma [PERSONA_1] con DNI 12345678Z.', findings: [{ detail: 'Bloqueado' }] });
  await app.apply();
  return nodes;
}

function leakHidden() {
  return document.getElementById('edit-leak-note').classList.contains('hidden');
}

test('blocked result: an unrelated edit does not flag values already in the preview', async () => {
  const nodes = fakeDom();
  await applyBlocked(nodes);
  assert.equal(nodes.get('download').disabled, false);

  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678Z. Nota añadida.');

  assert.equal(leakHidden(), true);
  assert.equal(nodes.get('download').disabled, false);
  assert.equal(nodes.get('copy').disabled, false);
  assert.equal(state.warningMarkdown, METADATA + 'Firma [PERSONA_1] con DNI 12345678Z. Nota añadida.');
});

test('blocked result: removing an existing occurrence is allowed', async () => {
  const nodes = fakeDom();
  await applyBlocked(nodes);

  typeInResult(nodes, 'Firma [PERSONA_1] con DNI [DNI_1].');

  assert.equal(leakHidden(), true);
  assert.equal(nodes.get('download').disabled, false);
});

test('blocked result: adding another occurrence of a still-anonymized value blocks, retyping beyond the baseline too', async () => {
  const nodes = fakeDom();
  await applyBlocked(nodes);

  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678Z y 12345678Z.');
  assert.equal(leakHidden(), false);
  assert.equal(nodes.get('download').disabled, true);
  assert.equal(nodes.get('copy').disabled, true);

  typeInResult(nodes, 'Firma [PERSONA_1] con DNI [DNI_1].');
  assert.equal(leakHidden(), true);
  assert.equal(nodes.get('download').disabled, false);
  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678Z. Ref 12345678Z');
  assert.equal(leakHidden(), false);
  assert.equal(nodes.get('download').disabled, true);
});

test('blocked result: a different anonymized value introduced by the edit blocks', async () => {
  const nodes = fakeDom();
  await applyBlocked(nodes);

  typeInResult(nodes, 'Firma JUAN con DNI 12345678Z.');

  assert.equal(leakHidden(), false);
  assert.equal(nodes.get('download').disabled, true);
});

test('deliverable result: typing an anonymized value still blocks (baseline 0)', async () => {
  const nodes = fakeDom();
  seedDni();
  respond({ deliverable: true, markdown: METADATA + 'Firma [PERSONA_1] con DNI [DNI_1].', substitutions: 2, entities: 2 });
  await app.apply();

  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678Z.');

  assert.equal(leakHidden(), false);
  assert.equal(nodes.get('download').disabled, true);
});

test('the baseline resets when a new result arrives', async () => {
  const nodes = fakeDom();
  await applyBlocked(nodes);
  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678Z. Nota.');
  assert.equal(leakHidden(), true);

  global.confirm = () => true;
  respond({ deliverable: false, markdown: METADATA + 'Firma [PERSONA_1] con DNI [DNI_1].', findings: [{ detail: 'Bloqueado' }] });
  await app.apply();
  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678Z.');

  assert.equal(leakHidden(), false);
  assert.equal(nodes.get('download').disabled, true);
});

test('the baseline resets when the result is invalidated', async () => {
  const nodes = fakeDom();
  await applyBlocked(nodes);
  app.invalidateResult();
  respond({ deliverable: true, markdown: METADATA + 'Firma [PERSONA_1] con DNI [DNI_1].', substitutions: 2, entities: 2 });
  await app.apply();

  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678Z.');

  assert.equal(leakHidden(), false);
});

test('blocked result: an additional overlapping match blocks both exports', async () => {
  const nodes = fakeDom();
  state.text = 'Ana Ana';
  state.detections = [{ id: 'p1', type: 'PERSONA', start: 0, end: 7, entityKey: 'ana' }];
  respond({ deliverable: false, markdown: METADATA + 'Titular: [PERSONA_1]. Referencia: Mariana Ana.', findings: [{ detail: 'Bloqueado' }] });
  await app.apply();
  assert.equal(nodes.get('download').disabled, false);

  typeInResult(nodes, 'Titular: [PERSONA_1]. Referencia: Mariana Ana Ana.');

  assert.equal(leakHidden(), false);
  assert.equal(nodes.get('download').disabled, true);
  assert.equal(nodes.get('copy').disabled, true);
});

test('blocked result: a retyped folded substring within baseline is exported as edited', async () => {
  const nodes = fakeDom();
  state.text = 'Perez Lopez';
  state.detections = [{ id: 'p1', type: 'PERSONA', start: 0, end: 11, entityKey: 'perez' }];
  respond({ deliverable: false, markdown: METADATA + 'Titular: [PERSONA_1]. Referencia: JUAN PEREZ LOPEZ.', findings: [{ detail: 'Bloqueado' }] });
  await app.apply();
  typeInResult(nodes, 'Titular: [PERSONA_1]. Referencia: omitida.');
  typeInResult(nodes, 'Titular: Pérez   López. Referencia: omitida.');

  assert.equal(leakHidden(), true);
  assert.equal(nodes.get('download').disabled, false);
  assert.equal(nodes.get('copy').disabled, false);
  assert.equal(state.resultStatus, 'blocked');
  const expected = METADATA + 'Titular: Pérez   López. Referencia: omitida.';
  let blob = null;
  global.URL = { createObjectURL: value => { blob = value; return 'blob:'; }, revokeObjectURL() {} };
  assert.equal(app.download(), true);
  assert.equal(await blob.text(), expected);
  let copied = null;
  global.navigator = { clipboard: { writeText: async value => { copied = value; } } };
  await app.copyMarkdown();
  assert.equal(copied, expected);
});

test('deliverable result: a superseded warning cannot restore a baseline', async () => {
  const nodes = fakeDom();
  seedDni();
  const resolvers = [];
  global.fetch = () => new Promise(done => resolvers.push(done));
  const first = app.apply();
  const second = app.apply();
  resolvers[1]({ ok: true, json: async () => ({ deliverable: true, markdown: METADATA + 'Firma [PERSONA_1] con DNI [DNI_1].', substitutions: 2, entities: 2 }) });
  await second;
  resolvers[0]({ ok: true, json: async () => ({ deliverable: false, markdown: METADATA + 'Firma [PERSONA_1] con DNI 12345678Z.', findings: [{ detail: 'Bloqueado' }] }) });
  await first;

  assert.equal(state.resultStatus, 'success');
  assert.equal(state.warningDownloadEligible, false);
  assert.equal(state.leakBaseline.size, 0);
  typeInResult(nodes, 'Firma [PERSONA_1] con DNI 12345678Z.');
  assert.equal(leakHidden(), false);
  assert.equal(nodes.get('download').disabled, true);
  assert.equal(nodes.get('copy').disabled, true);
});

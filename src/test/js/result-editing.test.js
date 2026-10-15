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

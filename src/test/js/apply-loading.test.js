'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const app = require('../../main/resources/web/app.js');
const { state } = app;

const root = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'app.css'), 'utf8');

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
    createElement() { return node(); },
    createTextNode: value => value,
    querySelectorAll: () => []
  };
  return nodes;
}

const shown = nodes => !nodes.get('apply-loading').classList.contains('hidden');

beforeEach(() => {
  delete global.document;
  delete global.navigator;
  state.jobId = 'job';
  state.markdown = '';
  state.warningMarkdown = '';
  state.warningDownloadEligible = false;
  state.resultEdited = false;
  state.resultStatus = 'pending';
  state.resultFindings = [];
  state.revision = 0;
  state.applyRequest = 0;
  state.editError = null;
});

test('apply markup is an Anonimusing status overlay outside the dropzone', () => {
  const match = html.match(/<div id="apply-loading"[\s\S]*?<\/div>/);
  assert.ok(match, 'apply-loading section missing');
  assert.match(match[0], /role="status"/);
  assert.match(match[0], /aria-live="polite"/);
  assert.match(match[0], /class="[^"]*\bhidden\b/);
  assert.match(match[0], /<svg class="loading-mark"[^>]*aria-hidden="true"/);
  assert.match(match[0], /<span class="loading-title">Anonimusing&hellip;<\/span>/);
  assert.match(match[0], /<span class="loading-sub">Anonimizando documento&hellip;<\/span>/);
  assert.doesNotMatch(html.match(/<section id="dropzone"[\s\S]*?<\/section>/)[0], /apply-loading/);
  assert.match(css, /\.apply-loading\s*\{[^}]*position:\s*fixed[^}]*inset:\s*0/);
});

test('overlay is visible while /api/apply is pending and hidden after success', async () => {
  const nodes = fakeDom();
  let resolve;
  global.fetch = () => new Promise(done => { resolve = done; });
  const pending = app.apply();
  assert.equal(shown(nodes), true);
  resolve({ ok: true, json: async () => ({ deliverable: true, markdown: 'safe', substitutions: 1, entities: 1 }) });
  await pending;
  assert.equal(shown(nodes), false);
});

test('overlay is hidden after a non-ok response and the error status is shown', async () => {
  const nodes = fakeDom();
  global.fetch = async () => ({ ok: false, json: async () => ({ error: 'boom' }) });
  const originalError = console.error; console.error = () => {};
  try { await app.apply(); } finally { console.error = originalError; }
  assert.equal(shown(nodes), false);
  assert.equal(state.resultStatus, 'error');
});

test('overlay is hidden after a network failure', async () => {
  const nodes = fakeDom();
  global.fetch = async () => { throw new TypeError('network'); };
  const originalError = console.error; console.error = () => {};
  try { await app.apply(); } finally { console.error = originalError; }
  assert.equal(shown(nodes), false);
  assert.equal(state.resultStatus, 'error');
});

test('overlay is hidden after invalid JSON', async () => {
  const nodes = fakeDom();
  global.fetch = async () => ({ ok: true, json: async () => { throw new SyntaxError('bad json'); } });
  const originalError = console.error; console.error = () => {};
  try { await app.apply(); } finally { console.error = originalError; }
  assert.equal(shown(nodes), false);
  assert.equal(state.resultStatus, 'error');
});

test('a superseded request neither hides nor leaves stuck the overlay of the newer request', async () => {
  const nodes = fakeDom();
  const resolvers = [];
  global.fetch = () => new Promise(done => resolvers.push(done));
  const first = app.apply();
  const second = app.apply();
  resolvers[0]({ ok: true, json: async () => ({ deliverable: true, markdown: 'old' }) });
  await first;
  assert.equal(shown(nodes), true, 'stale request must not hide the newer overlay');
  resolvers[1]({ ok: true, json: async () => ({ deliverable: true, markdown: 'new' }) });
  await second;
  assert.equal(shown(nodes), false);
});

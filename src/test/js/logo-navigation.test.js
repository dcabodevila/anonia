'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const source = fs.readFileSync(path.join(root, 'app.js'), 'utf8');

function fixture() {
  const nodes = new Map();
  const node = id => {
    if (!nodes.has(id)) {
      const classes = new Set(id === 'workspace' || id === 'loading' || id === 'error' ? ['hidden'] : []);
      nodes.set(id, {
        classList: { add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name) },
        addEventListener(name, callback) { this[name] = callback; },
        click() { if (this.clickHandler) this.clickHandler(); },
        textContent: '', value: '', files: [], disabled: false
      });
    }
    return nodes.get(id);
  };
  const context = vm.createContext({ document: { getElementById: node, querySelectorAll: () => [] }, console });
  vm.runInContext(source, context);
  vm.runInContext('init()', context);
  return { node, context };
}

test('brand is a native keyboard-usable home control', () => {
  assert.match(html, /<button[^>]*id="brand-home"[^>]*type="button"[^>]*aria-label="[^"]+"/);
});

test('brand click resets active analysis and ignores a late analysis response', async () => {
  const { node, context } = fixture();
  let resolve;
  context.fetch = () => new Promise(done => { resolve = done; });
  const pending = vm.runInContext('analyze({ name: "secret.pdf" })', context);
  node('workspace').classList.remove('hidden');
  node('error').classList.remove('hidden');
  node('dropzone').classList.add('compact');
  node('file').value = 'secret.pdf';
  node('brand-home').click();
  assert.equal(node('workspace').classList.contains('hidden'), true);
  assert.equal(node('loading').classList.contains('hidden'), true);
  assert.equal(node('error').classList.contains('hidden'), true);
  assert.equal(node('dropzone').classList.contains('compact'), false);
  assert.equal(node('file').value, '');
  assert.equal(vm.runInContext('state.jobId === null && state.sourceFilename === null && state.text === ""', context), true);
  resolve({ ok: true, json: async () => ({ jobId: 'late', text: 'secret', detections: [], types: [] }) });
  await pending;
  assert.equal(node('workspace').classList.contains('hidden'), true);
  assert.equal(vm.runInContext('state.jobId', context), null);
});

test('the page heading keeps the product name in its accessible name', () => {
  const html = require('node:fs').readFileSync(require('node:path').resolve(__dirname, '../../main/resources/web/index.html'), 'utf8');
  assert.match(html, /<h1 class="brand-lockup"><button[^>]*id="brand-home"[^>]*aria-label="anonimuse, volver al inicio"/);
});

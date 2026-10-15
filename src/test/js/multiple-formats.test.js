'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/web/app.js'), 'utf8');

function fixture() {
  const nodes = new Map();
  const node = id => {
    if (!nodes.has(id)) {
      const classes = new Set();
      nodes.set(id, {
        classList: { add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name), toggle() {} },
        addEventListener() {}, setAttribute() {}, removeAttribute() {}, appendChild() {}, querySelectorAll: () => [],
        style: {}, dataset: {}
      });
    }
    return nodes.get(id);
  };
  const requests = [];
  const context = vm.createContext({
    document: { getElementById: node, querySelectorAll: () => [], addEventListener() {}, createElement: () => node(Symbol()) },
    console,
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: false, json: async () => ({ error: 'stop' }) };
    }
  });
  vm.runInContext(source, context);
  return { context, requests };
}

test('analysis uploads declare the encoded file name so text formats can be recognised', async () => {
  const { context, requests } = fixture();

  await context.analyze({ name: 'acta final ñ.md' });

  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/analyze');
  assert.equal(requests[0].options.method, 'POST');
  assert.equal((requests[0].options.headers || {})['X-File-Name'], encodeURIComponent('acta final ñ.md'));
});

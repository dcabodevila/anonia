'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/web/app.js'), 'utf8');

function fixture() {
  const listeners = new Map();
  const nodes = new Map();
  const node = id => {
    if (!nodes.has(id)) {
      const classes = new Set();
      nodes.set(id, {
        classList: { add: name => classes.add(name), remove: name => classes.delete(name), contains: name => classes.has(name) },
        addEventListener() {}
      });
    }
    return nodes.get(id);
  };
  const analyzed = [];
  const context = vm.createContext({ document: {
    getElementById: node, querySelectorAll: () => [],
    addEventListener(name, callback) {
      if (!listeners.has(name)) listeners.set(name, []);
      listeners.get(name).push(callback);
    }
  }, console, recordAnalysis: file => analyzed.push(file) });
  vm.runInContext(source, context);
  vm.runInContext('analyze = recordAnalysis', context);
  const dispatch = (name, dataTransfer) => {
    let prevented = false;
    const event = { dataTransfer, preventDefault() { prevented = true; } };
    for (const listener of listeners.get(name) || []) listener(event);
    return prevented;
  };
  return { dispatch, analyzed, dragging: () => node('dropzone').classList.contains('dragging') };
}

test('text drags retain native handling without upload feedback or analysis', () => {
  const { dispatch, analyzed, dragging } = fixture();
  const transfer = { types: ['text/plain'], files: [] };
  for (const name of ['dragenter', 'dragover', 'dragleave', 'drop']) {
    assert.equal(dispatch(name, transfer), false, `${name} must not prevent native text drop`);
    assert.equal(dragging(), false);
  }
  assert.equal(analyzed.length, 0);
});

test('file drags show feedback and analyze the first dropped file exactly once', () => {
  const { dispatch, analyzed, dragging } = fixture();
  const file = { name: 'document.pdf' };
  const transfer = { types: ['Files', 'text/plain'], files: [file, { name: 'other.pdf' }] };
  for (const name of ['dragenter', 'dragover']) {
    assert.equal(dispatch(name, transfer), true);
    assert.equal(dragging(), true);
  }
  assert.equal(dispatch('dragleave', transfer), true);
  assert.equal(dragging(), false);
  dispatch('dragenter', transfer);
  assert.equal(dispatch('drop', transfer), true);
  assert.equal(dragging(), false);
  assert.deepEqual(analyzed, [file]);
});

test('empty file drops and events without a transfer never analyze', () => {
  const { dispatch, analyzed, dragging } = fixture();
  assert.equal(dispatch('drop', { types: ['Files'], files: [] }), true);
  for (const name of ['dragenter', 'dragover', 'dragleave', 'drop']) {
    assert.equal(dispatch(name, null), false);
  }
  assert.equal(dragging(), false);
  assert.equal(analyzed.length, 0);
});

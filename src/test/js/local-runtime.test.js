'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'app.css'), 'utf8');
const source = fs.readFileSync(path.join(root, 'app.js'), 'utf8');

function boot(hostname) {
  const nodes = new Map();
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, { classList: { add() {}, remove() {}, contains: () => false },
      addEventListener() {}, textContent: '', value: '', files: [] });
    return nodes.get(id);
  };
  const documentElement = { dataset: {} };
  const context = vm.createContext({
    document: { getElementById: node, querySelectorAll: () => [], documentElement },
    location: { hostname }, console
  });
  vm.runInContext(source, context);
  vm.runInContext('init()', context);
  return documentElement.dataset;
}

test('a page served from this machine is marked as a local runtime', () => {
  for (const hostname of ['127.0.0.1', 'localhost', '[::1]']) {
    assert.equal(boot(hostname).runtime, 'local', hostname);
  }
});

test('a page served from a remote host keeps the web demo presentation', () => {
  assert.equal(boot('anonimuse.example.com').runtime, undefined);
});

test('local runtime hides the download path and the web-vs-desktop comparison', () => {
  for (const selector of ['.hero-download', '.hero-try-heading', '.hero-try-note',
    '.landing-compare-heading', '.landing-compare-columns', '.landing-cta']) {
    const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    assert.match(css, new RegExp(':root\\[data-runtime="local"\\] ' + escaped + '[\\s,{]'), selector);
  }
  assert.match(css, /:root\[data-runtime="local"\] \.hero-local-note\s*\{[^}]*display:\s*block/);
  // The no-storage / no-AI / offline guarantees stay visible on the desktop app.
  assert.doesNotMatch(css, /:root\[data-runtime="local"\] \.landing-(compare|guarantees)[\s,{]/);
  assert.match(css, /\.hero-local-note\s*\{[^}]*display:\s*none/);
  assert.match(html, /<p class="hero-local-note">Tu documento se analiza en este equipo y nunca sale de él\.<\/p>/);
});

test('translucent colors are defined once as tokens in :root', () => {
  const rootBlock = css.match(/:root\s*\{[^}]*\}/)[0];
  const rest = css.replace(rootBlock, '');
  assert.doesNotMatch(rest, /rgba\(/);
  for (const token of ['--error-border', '--error-soft', '--warning-border', '--warning-soft', '--hover-tint', '--shadow-deep']) {
    assert.match(rootBlock, new RegExp(token + ':'), token);
  }
});

'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const app = require('../../main/resources/web/app.js');
const { state } = app;

// Minimal DOM: enough for renderDocument to build marks without a browser.
function fakeDom() {
  const nodes = new Map();
  const node = () => ({
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    style: {}, dataset: {}, attributes: {}, children: [],
    append(...children) { this.children.push(...children); },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    addEventListener() {}, querySelector() { return null; },
    set innerHTML(_) { this.children = []; }, get innerHTML() { return ''; }
  });
  global.document = {
    documentElement: node(),
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    createElement: node, createTextNode: value => value,
    querySelectorAll() { return []; }
  };
  const reads = [];
  // Each getPropertyValue on a dirty DOM forces a full style recalculation in a browser.
  global.getComputedStyle = () => ({
    getPropertyValue(key) { reads.push(key); return key === '--t-persona' ? ' #ffb1dd ' : ''; }
  });
  return { nodes, reads };
}

function detections(count) {
  const words = [];
  const list = [];
  let cursor = 0;
  for (let i = 0; i < count; i++) {
    const word = 'Valor' + i;
    const type = ['PERSONA', 'DNI', 'EMAIL'][i % 3];
    list.push({ id: 'd' + i, type, start: cursor, end: cursor + word.length,
      entityKey: 'k' + i, provenance: 'SOURCE', confidence: 0.9 });
    words.push(word);
    cursor += word.length + 1;
  }
  return { text: words.join(' '), list };
}

beforeEach(() => {
  delete global.document;
  state.rejected = new Set();
  state.edits = [];
  state.comparing = false;
  state.activeOccurrenceId = null;
  state.activeEntityKey = null;
});

test('rendering many marks reads each type color at most once instead of once per mark', () => {
  const { nodes, reads } = fakeDom();
  const { text, list } = detections(300);
  state.text = text;
  state.detections = list;
  app.groupEntities();

  app.renderDocument();
  const marks = nodes.get('doctext').children.filter(child => typeof child === 'object');
  assert.equal(marks.length, 300);
  assert.ok(reads.length <= 3, `expected at most one read per type, got ${reads.length}`);

  app.renderDocument();
  assert.ok(reads.length <= 3, `a second render must reuse the colors, got ${reads.length} reads`);

  // Same colors as before: the CSS variable when present, the fallback otherwise.
  assert.equal(marks.find(mark => mark.textContent === 'Valor0').style.color, '#ffb1dd');
  assert.equal(marks.find(mark => mark.textContent === 'Valor1').style.color, '#8b97a8');
});

test('occurrenceIds orders and deduplicates ranges without scanning all detections per comparison', () => {
  fakeDom();
  const { text, list } = detections(400);
  // One entity with occurrences listed in reverse order plus a duplicated range.
  const members = list.slice(0, 200).map(d => ({ ...d, entityKey: 'same' }));
  members.push({ ...members[5], id: 'dup' });
  state.text = text;
  state.detections = [...members.reverse(), ...list.slice(200)];
  app.groupEntities();

  let scans = 0;
  const find = Array.prototype.find;
  state.detections.find = function (...args) { scans++; return find.apply(this, args); };

  const ids = app.occurrenceIds('same');

  assert.equal(ids.length, 200, 'duplicate range collapsed');
  assert.deepEqual(ids.slice(0, 3), ['d0', 'd1', 'd2']);
  assert.equal(ids.includes('dup') && ids.includes('d5'), false, 'only one id per range');
  assert.ok(scans <= 1, `expected an id index, got ${scans} linear scans`);
});

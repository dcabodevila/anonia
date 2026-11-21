'use strict';
const { test, beforeEach, afterEach } = require('node:test');
const assert = require('node:assert/strict');
const app = require('../../main/resources/web/app.js');
const { state } = app;

// Minimal DOM: enough for renderEntities/renderDocument to run without a browser.
function fakeDom() {
  const nodes = new Map();
  const node = () => ({
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    style: {}, dataset: {}, attributes: {}, children: [], disabled: false, value: '',
    append(...children) { this.children.push(...children); },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    addEventListener() {}, querySelector() { return null; }, remove() {},
    set innerHTML(_) { this.children = []; }, get innerHTML() { return ''; }
  });
  global.document = {
    documentElement: node(),
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    createElement: node, createTextNode: value => value,
    querySelectorAll() { return []; }
  };
  global.getComputedStyle = () => ({ getPropertyValue: () => '' });
  return nodes;
}

const VALUES = [['Ana', 'PERSONA'], ['Anabel', 'PERSONA'], ['ana@x.es', 'EMAIL'], ['Luis', 'PERSONA'], ['luis@x.es', 'EMAIL']];
const key = i => 'k' + i;
let nodes;

function fixture() {
  let text = '';
  state.detections = VALUES.map(([value, type], i) => {
    const start = text.length;
    text += value + ' / ';
    return { id: 'd' + i, type, start, end: start + value.length, entityKey: key(i), provenance: 'test', confidence: 1 };
  });
  state.text = text;
  state.types = ['PERSONA', 'EMAIL'];
  state.edits = [];
  state.rejected = new Set();
  state.entitySearch = '';
  state.entityTypeFilter = '';
  state.activeEntityKey = null;
  state.activeOccurrenceId = null;
  state.comparing = false;
  app.groupEntities();
}

beforeEach(() => { nodes = fakeDom(); fixture(); });
afterEach(() => { delete global.document; delete global.getComputedStyle; });

const rejected = () => [...state.rejected].sort();
const button = () => nodes.get('bulk-uncheck');

test('S3a only entities listed by the filters are rejected; hidden ones keep their state', () => {
  state.rejected.add(key(3));            // hidden and already unchecked
  app.setEntityFilters('ana', '');       // lists Ana, Anabel, ana@x.es; hides checked Luis items
  app.uncheckFilteredEntities();
  assert.deepEqual(rejected(), [key(0), key(1), key(2), key(3)]);
  assert.equal(state.rejected.has(key(4)), false, 'hidden checked entity stays checked');
});

test('S3a text and type filters combine', () => {
  app.setEntityFilters('*x.es', 'EMAIL');
  app.uncheckFilteredEntities();
  assert.deepEqual(rejected(), [key(2), key(4)]);
  fixture();
  app.setEntityFilters('ana', 'EMAIL');
  app.uncheckFilteredEntities();
  assert.deepEqual(rejected(), [key(2)]);
});

test('S3b with no filters every listed entity is rejected, once each in the edit log', () => {
  state.rejected.add(key(1));
  app.uncheckFilteredEntities();
  assert.deepEqual(rejected(), [0, 1, 2, 3, 4].map(key));
  assert.deepEqual(state.edits.map(e => e[1]).sort(), ['d0', 'd2', 'd3', 'd4']);
  assert.ok(state.edits.every(e => e[0] === 'reject' && e[2] === 'true'));
});

test('S3b the button is disabled only when the list is empty; all-unchecked lists switch it to Marcar todos', () => {
  app.renderEntities();
  assert.equal(button().disabled, false);
  assert.equal(button().textContent, 'Desmarcar todos');
  app.setEntityFilters('luis', '');
  app.uncheckFilteredEntities();
  assert.equal(button().disabled, false, 'listed entities all unchecked: Marcar todos is available');
  app.setEntityFilters('', '');
  app.renderEntities();
  assert.equal(button().disabled, false);
  assert.equal(button().textContent, 'Desmarcar todos', 'hidden-then-listed checked entities');
  app.setEntityFilters('nada', '');
  app.renderEntities();
  assert.equal(button().disabled, true, 'empty list');
  assert.equal(button().textContent, 'Desmarcar todos');
  const before = state.revision;
  app.uncheckFilteredEntities();
  app.checkFilteredEntities();
  assert.deepEqual(rejected(), [key(3), key(4)]);
  assert.equal(state.revision, before, 'a no-op does not refresh');
});

test('S3b the button follows entity toggles without a full render', () => {
  app.setEntityFilters('luis', '');
  app.renderEntities();
  assert.equal(button().disabled, false);
  app.uncheckFilteredEntities();
  assert.equal(button().textContent, 'Marcar todos');
  state.rejected.delete(key(3));
  app.refreshSelection(key(3));
  assert.equal(button().textContent, 'Desmarcar todos');
});

test('S3c the review refreshes once: result invalidated, rows and document marks redrawn', () => {
  app.renderEntities();
  app.renderDocument();
  const marks = () => nodes.get('doctext').children.filter(child => typeof child === 'object').length;
  assert.equal(marks(), VALUES.length);
  const before = state.revision;
  app.setEntityFilters('luis', '');       // includes the entity of the final document mark
  app.uncheckFilteredEntities();
  assert.equal(state.revision, before + 1, 'single refresh for the whole batch');
  assert.equal(marks(), VALUES.length - 2, 'earlier marks are kept when the final mark is rejected');
  app.setEntityFilters('', '');
  app.uncheckFilteredEntities();
  assert.equal(state.revision, before + 2);
  assert.equal(marks(), 0, 'every rejected mark leaves the document');
});

test('Marcar todos: label switches when all listed are unchecked; hidden entities are ignored', () => {
  state.rejected.add(key(3)); state.rejected.add(key(4));   // Luis items unchecked
  app.setEntityFilters('luis', '');
  app.renderEntities();
  assert.equal(button().textContent, 'Marcar todos');
  app.setEntityFilters('', '');
  app.renderEntities();
  assert.equal(button().textContent, 'Desmarcar todos', 'hidden decision ignored: others are checked');
  state.rejected = new Set([0, 1, 2].map(key));              // only hidden ones are checked
  app.setEntityFilters('luis', '');
  app.renderEntities();
  assert.equal(button().textContent, 'Desmarcar todos');
  state.rejected = new Set([3, 4].map(key));
  app.setEntityFilters('luis', '');
  app.renderEntities();
  assert.equal(button().textContent, 'Marcar todos');
  assert.equal(button().disabled, false);
});

test('Marcar todos: re-checks only listed entities, records false edits, refreshes once', () => {
  state.rejected = new Set([0, 1, 2, 3, 4].map(key));
  state.edits = [];
  app.setEntityFilters('ana', '');
  app.renderEntities();
  const before = state.revision;
  app.checkFilteredEntities();
  assert.deepEqual(rejected(), [key(3), key(4)], 'hidden entities keep their state');
  assert.deepEqual(state.edits.map(e => e[1]).sort(), ['d0', 'd1', 'd2']);
  assert.ok(state.edits.every(e => e[0] === 'reject' && e[2] === 'false'));
  assert.equal(state.revision, before + 1);
  assert.equal(button().textContent, 'Desmarcar todos');
});

test('Marcar todos: the button action follows the label', () => {
  app.setEntityFilters('luis', '');
  app.uncheckFilteredEntities();
  app.toggleFilteredEntities();
  assert.deepEqual(rejected(), [], 'all-unchecked list is re-checked');
  app.toggleFilteredEntities();
  assert.deepEqual(rejected(), [key(3), key(4)], 'checked list is unchecked');
});

test('Marcar todos: toggling one item back switches the label to Desmarcar todos', () => {
  app.setEntityFilters('luis', '');
  app.uncheckFilteredEntities();
  assert.equal(button().textContent, 'Marcar todos');
  app.toggleEntity(key(3));
  assert.equal(button().textContent, 'Desmarcar todos');
});

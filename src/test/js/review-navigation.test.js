'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const app = require('../../main/resources/web/app.js');
const { state } = app;

function fixture(text, detections) {
  state.text = text;
  state.detections = detections.map(([id, type, start, end, entityKey]) =>
    ({ id, type, start, end, entityKey, provenance: 'test', confidence: 1 }));
  state.types = ['PERSONA', 'DIRECCION', 'EMAIL'];
  state.edits = [];
  state.rejected = new Set();
  app.groupEntities();
}

beforeEach(() => {
  state.activeEntityKey = null;
  state.activeOccurrenceId = null;
  state.entitySearch = '';
  state.entityTypeFilter = '';
});

test('normalized case-insensitive filters only narrow the list and never mutate review edits', () => {
  fixture('María  García / María García / Calle Mayor', [
    ['p1', 'PERSONA', 0, 13, 'person'],
    ['p2', 'PERSONA', 16, 28, 'person'],
    ['a1', 'DIRECCION', 31, 42, 'address']
  ]);
  state.rejected.add('address');
  state.revision = 42;
  state.markdown = 'already generated';
  state.warningMarkdown = 'warning export';
  state.warningDownloadEligible = true;
  const before = JSON.stringify([state.detections, [...state.rejected], state.edits, app.effectiveDetections(),
    state.revision, state.markdown, state.warningMarkdown, state.warningDownloadEligible]);

  app.setEntityFilters('  maría garcía ', 'PERSONA');

  assert.deepEqual(app.filteredEntities().map(([key]) => key), ['person']);
  assert.equal(JSON.stringify([state.detections, [...state.rejected], state.edits, app.effectiveDetections(),
    state.revision, state.markdown, state.warningMarkdown, state.warningDownloadEligible]), before);
});

test('per-entity occurrence navigation wraps while leaving rejected and nested projection untouched', () => {
  fixture('Calle María García; Calle María García', [
    ['outer', 'DIRECCION', 0, 18, 'address'],
    ['p1', 'PERSONA', 6, 18, 'person'],
    ['p2', 'PERSONA', 26, 38, 'person']
  ]);
  state.rejected.add('address');
  const projection = app.effectiveDetections().map(detection => detection.id);
  const before = JSON.stringify([state.detections, [...state.rejected], state.edits]);

  assert.deepEqual(app.selectEntityLocation('person'), { id: 'p1', index: 0, count: 2, wrapped: false });
  assert.deepEqual(app.navigateEntityOccurrence(-1), { id: 'p2', index: 1, count: 2, wrapped: true });
  assert.deepEqual(app.navigateEntityOccurrence(1), { id: 'p1', index: 0, count: 2, wrapped: true });
  assert.deepEqual(app.effectiveDetections().map(detection => detection.id), projection);
  assert.equal(JSON.stringify([state.detections, [...state.rejected], state.edits]), before);

  assert.deepEqual(app.selectEntityLocation('address'), { id: 'outer', index: 0, count: 1, wrapped: false });
  assert.deepEqual(app.effectiveDetections().map(detection => detection.id), projection);
});

test('highlight activation treats Enter and Space as the existing click action', () => {
  assert.equal(app.isHighlightActivation({ key: 'Enter' }), true);
  assert.equal(app.isHighlightActivation({ key: ' ' }), true);
  assert.equal(app.isHighlightActivation({ key: 'Spacebar' }), true);
  assert.equal(app.isHighlightActivation({ key: 'Escape' }), false);
});

test('saved control focus is restored after a rerender, with the filter as fallback', () => {
  let restored = 0;
  let fallback = 0;
  const control = { dataset: { focusTarget: 'type:person' }, focus() { restored++; } };
  global.document = {
    querySelectorAll() { return [control]; },
    getElementById() { return { focus() { fallback++; } }; }
  };

  state.focusTarget = 'type:person';
  app.restoreFocus();
  assert.equal(restored, 1);
  assert.equal(fallback, 0);

  state.focusTarget = 'locate:filtered-out';
  app.restoreFocus();
  assert.equal(fallback, 1);
  delete global.document;
});

function renderedDom() {
  const nodes = new Map();
  const makeClassList = () => ({ add() {}, remove() {}, toggle() {}, contains() { return false; } });
  const node = () => {
    let children = [];
    return {
      dataset: {}, attributes: {}, style: {}, listeners: {}, classList: makeClassList(), focusCount: 0,
      get children() { return children; }, set children(value) { children = value; },
      get innerHTML() { return ''; }, set innerHTML(value) { children = []; },
      append(...items) { children.push(...items); },
      setAttribute(name, value) { this.attributes[name] = String(value); },
      addEventListener(name, listener) { this.listeners[name] = listener; },
      focus() { this.focusCount++; }, select() {},
      querySelector() { return undefined; }
    };
  };
  global.document = {
    documentElement: {},
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); },
    createElement: node,
    createTextNode: value => value,
    querySelectorAll() {
      const all = [];
      const visit = value => {
        if (!value || typeof value === 'string') return;
        all.push(value);
        value.children.forEach(visit);
      };
      nodes.forEach(visit);
      return all;
    }
  };
  global.getComputedStyle = () => ({ getPropertyValue: () => '' });
  return nodes;
}

const renderedText = node => node.children.map(child => typeof child === 'string' ? child : child.textContent).join('');

test('Enter toggles a real rendered highlight and restores focus to its rejected location marker', () => {
  const nodes = renderedDom();
  fixture('María García', [['p1', 'PERSONA', 0, 12, 'person']]);
  state.revision = 0;
  app.renderDocument();
  const original = nodes.get('doctext').children[0];

  original.listeners.keydown({ key: 'Enter', preventDefault() {} });

  const marker = nodes.get('doctext').children.find(child => typeof child !== 'string'
    && child.dataset.focusTarget === 'highlight:p1');
  assert.equal(state.rejected.has('person'), true);
  assert.equal(state.activeOccurrenceId, 'p1');
  assert.equal(marker.focusCount, 1);
  assert.equal(renderedText(nodes.get('doctext')), state.text);
  delete global.document;
  delete global.getComputedStyle;
});

test('active accepted nested locations segment the rendered original without changing semantic projection', () => {
  const nodes = renderedDom();
  fixture('Calle María García / Calle María García', [
    ['outer', 'DIRECCION', 0, 18, 'address'],
    ['p1', 'PERSONA', 6, 18, 'person'],
    ['p1-duplicate', 'PERSONA', 6, 18, 'person'],
    ['p2', 'PERSONA', 27, 39, 'person']
  ]);
  const projection = app.effectiveDetections().map(detection => detection.id);
  const reviewState = JSON.stringify([state.detections, [...state.rejected], state.edits]);

  app.selectEntityLocation('person');
  app.renderDocument();

  const marks = nodes.get('doctext').children.filter(child => typeof child !== 'string');
  assert.equal(renderedText(nodes.get('doctext')), state.text);
  assert.deepEqual(app.effectiveDetections().map(detection => detection.id), projection);
  assert.equal(JSON.stringify([state.detections, [...state.rejected], state.edits]), reviewState);
  assert.deepEqual(marks.map(mark => [mark.textContent, mark.className, mark.dataset.occurrenceId || null]), [
    ['Calle ', 'hl', null],
    ['María García', 'hl active-location', 'p1'],
    ['María García', 'hl', null]
  ]);
  delete global.document;
  delete global.getComputedStyle;
});

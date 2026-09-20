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
      dataset: {}, attributes: {}, style: {}, listeners: {}, classList: makeClassList(), focusCount: 0, scrollTop: 0,
      get children() { return children; }, set children(value) { children = value; },
      get innerHTML() { return ''; }, set innerHTML(value) { children = []; },
      append(...items) { children.push(...items); },
      setAttribute(name, value) { this.attributes[name] = String(value); },
      addEventListener(name, listener) { this.listeners[name] = listener; },
      focus(options) { this.focusCount++; this.focusOptions = options; }, select() {},
      scrollTo(options) { this.scrollToCalls = (this.scrollToCalls || []).concat(options); },
      scrollIntoView(options) { this.scrollIntoViewCalls = (this.scrollIntoViewCalls || []).concat(options); },
      getBoundingClientRect() { return { top: 20, height: 40 }; },
      querySelector(selector) {
        const occurrence = selector.match(/^\[data-occurrence-id="(.+)"\]$/);
        if (!occurrence) return undefined;
        return this.children.find(child => typeof child !== 'string'
          && child.dataset.occurrenceId === occurrence[1]);
      }
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
const controlsByTarget = () => [...document.querySelectorAll('[data-focus-target]')]
  .reduce((controls, control) => Object.assign(controls, { [control.dataset.focusTarget]: control }), {});

test('only the active multi-occurrence row renders local navigation that restores button focus and announces wrapping', () => {
  const nodes = renderedDom();
  fixture('Ana / Ana / Calle', [
    ['p1', 'PERSONA', 0, 3, 'person'], ['p2', 'PERSONA', 6, 9, 'person'],
    ['a1', 'DIRECCION', 12, 17, 'address']
  ]);
  app.selectEntityLocation('person');
  app.renderEntities();

  const personRow = [...document.querySelectorAll('[data-entity-key]')]
    .find(row => row.dataset.entityKey === 'person');
  assert.equal(personRow.children[1].children[1].textContent, '2 apariciones');
  let controls = controlsByTarget();
  assert.ok(controls['previous:person']);
  assert.ok(controls['next:person']);
  assert.equal(controls['previous:address'], undefined);
  assert.equal(controls['next:address'], undefined);

  controls['next:person'].listeners.click();
  controls = controlsByTarget();
  assert.equal(state.activeOccurrenceId, 'p2');
  assert.equal(controls['next:person'].focusCount, 1);
  assert.equal(nodes.get('doctext').children.find(child => typeof child !== 'string'
    && child.dataset.occurrenceId === 'p2').scrollIntoViewCalls[0].behavior, 'smooth');

  controls['next:person'].listeners.click();
  assert.equal(state.activeOccurrenceId, 'p1');
  assert.match(nodes.get('location-status').textContent, /Vuelta al inicio\./);

  controls = controlsByTarget();
  controls['previous:person'].listeners.click();
  controls = controlsByTarget();
  assert.equal(state.activeOccurrenceId, 'p2');
  assert.equal(controls['previous:person'].focusCount, 1);
  assert.match(nodes.get('location-status').textContent, /Vuelta al final\./);
  delete global.document;
  delete global.getComputedStyle;
});

for (const [input, eventName] of [[
  'click', 'click'
], [
  'Enter', 'keydown'
], [
  ' ', 'keydown'
]]) test('marker ' + input + ' navigation skips marker scroll and finalizes matching row focus and scroll', () => {
  const nodes = renderedDom();
  const frames = [];
  global.requestAnimationFrame = callback => { frames.push(callback); return frames.length; };
  fixture('María García', [['p1', 'PERSONA', 0, 12, 'person']]);
  state.revision = 7;
  state.markdown = 'already generated';
  state.warningMarkdown = 'warning export';
  state.warningDownloadEligible = true;
  app.renderDocument();
  const original = nodes.get('doctext').children[0];
  const before = JSON.stringify([state.detections, [...state.rejected], state.edits, state.revision,
    state.markdown, state.warningMarkdown, state.warningDownloadEligible]);
  let prevented = 0;

  if (eventName === 'click') original.listeners.click({ preventDefault() { prevented++; } });
  else original.listeners.keydown({ key: input, preventDefault() { prevented++; } });

  const locate = [...document.querySelectorAll('[data-focus-target]')]
    .find(control => control.dataset.focusTarget === 'locate:person');
  const row = [...document.querySelectorAll('[data-entity-key]')]
    .find(candidate => candidate.dataset.entityKey === 'person');
  assert.equal(JSON.stringify([state.detections, [...state.rejected], state.edits, state.revision,
    state.markdown, state.warningMarkdown, state.warningDownloadEligible]), before);
  assert.equal(state.activeEntityKey, 'person');
  assert.equal(state.activeOccurrenceId, 'p1');
  assert.equal(prevented, 1);
  assert.equal(nodes.get('doctext').scrollIntoViewCalls, undefined);
  assert.equal(locate.focusCount, 0);
  assert.equal(frames.length, 1);

  frames.shift()();

  assert.equal(locate.focusCount, 1);
  assert.deepEqual(locate.focusOptions, { preventScroll: true });
  assert.deepEqual(nodes.get('entities').scrollToCalls, [{ top: 0, behavior: 'auto' }]);
  assert.match(nodes.get('location-status').textContent, /María García: aparición 1 de 1/);
  assert.equal(renderedText(nodes.get('doctext')), state.text);
  assert.ok(row);
  delete global.requestAnimationFrame;
  delete global.document;
  delete global.getComputedStyle;
});

test('marker navigation page-scrolls the matching row on narrow layouts after focus finalization', () => {
  const nodes = renderedDom();
  const frames = [];
  global.requestAnimationFrame = callback => { frames.push(callback); return frames.length; };
  global.window = { matchMedia: () => ({ matches: true }) };
  fixture('María García', [['p1', 'PERSONA', 0, 12, 'person']]);
  app.renderDocument();

  nodes.get('doctext').children[0].listeners.click({ preventDefault() {} });
  frames.shift()();

  const row = [...document.querySelectorAll('[data-entity-key]')]
    .find(candidate => candidate.dataset.entityKey === 'person');
  assert.deepEqual(row.scrollIntoViewCalls, [{ block: 'center', inline: 'nearest', behavior: 'auto' }]);
  assert.equal(nodes.get('entities').scrollToCalls, undefined);
  delete global.window;
  delete global.requestAnimationFrame;
  delete global.document;
  delete global.getComputedStyle;
});

test('explicit entity navigation continues to scroll the active document occurrence', () => {
  const nodes = renderedDom();
  fixture('María García', [['p1', 'PERSONA', 0, 12, 'person']]);
  app.selectEntityLocation('person');

  app.renderDocument();

  const location = nodes.get('doctext').children.find(child => typeof child !== 'string'
    && child.dataset.occurrenceId === 'p1');
  assert.deepEqual(location.scrollIntoViewCalls, [{ block: 'center', inline: 'nearest', behavior: 'smooth' }]);
  delete global.document;
  delete global.getComputedStyle;
});

test('pointer marker activation preserves filters and announces when its selected row is hidden', () => {
  const nodes = renderedDom();
  fixture('María García / María García', [
    ['p1', 'PERSONA', 0, 12, 'person'], ['p2', 'PERSONA', 15, 27, 'person']
  ]);
  state.revision = 9;
  state.markdown = 'ready';
  app.setEntityFilters('sin coincidencias', '');
  app.renderDocument();
  const original = nodes.get('doctext').children[0];

  original.listeners.click({ preventDefault() {} });

  assert.equal(state.revision, 9);
  assert.equal(state.markdown, 'ready');
  assert.equal(state.rejected.has('person'), false);
  assert.equal(state.activeOccurrenceId, 'p1');
  assert.match(nodes.get('location-status').textContent, /no coincide con el filtro actual/);
  assert.equal(nodes.get('entities').children.length, 0);
  assert.equal(controlsByTarget()['previous:person'], undefined);
  assert.equal(controlsByTarget()['next:person'], undefined);
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

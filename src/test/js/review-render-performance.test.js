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

// Tree DOM with parent links, so DOM identity across a checkbox toggle can be asserted.
function treeDom() {
  const roots = new Map();
  const counts = { elements: 0, texts: 0 };
  const detach = item => {
    if (!item.parentNode) return;
    const siblings = item.parentNode.childNodes;
    siblings.splice(siblings.indexOf(item), 1);
    item.parentNode = null;
  };
  const shared = {
    remove() { detach(this); },
    before(...items) { for (const item of items) this.parentNode.insertBefore(item, this); }
  };
  const make = properties => Object.create(shared, Object.getOwnPropertyDescriptors(properties));
  const text = data => make({ nodeType: 3, data, parentNode: null,
    get textContent() { return this.data; } });
  const element = tagName => make({
    nodeType: 1, tagName, childNodes: [], parentNode: null, dataset: {}, attributes: {}, style: {},
    listeners: {}, className: '', ownText: '', focusCount: 0,
    get children() { return this.childNodes.filter(child => child.nodeType === 1); },
    get isConnected() { let node = this; while (node.parentNode) node = node.parentNode; return [...roots.values()].includes(node); },
    get firstChild() { return this.childNodes[0] || null; },
    get lastChild() { return this.childNodes[this.childNodes.length - 1] || null; },
    get textContent() { return this.ownText + this.childNodes.map(child => child.textContent).join(''); },
    set textContent(value) { this.innerHTML = ''; this.ownText = String(value); },
    get innerHTML() { return ''; },
    set innerHTML(_) { [...this.childNodes].forEach(child => detach(child)); this.ownText = ''; },
    get classList() {
      const owner = this;
      const names = () => owner.className.split(' ').filter(Boolean);
      return {
        add(name) { if (!names().includes(name)) owner.className = names().concat(name).join(' '); },
        remove(name) { owner.className = names().filter(other => other !== name).join(' '); },
        toggle(name, force) { if (force ?? !names().includes(name)) this.add(name); else this.remove(name); },
        contains(name) { return names().includes(name); }
      };
    },
    append(...items) {
      for (const item of items) {
        const child = typeof item === 'string' ? text(item) : item;
        detach(child);
        child.parentNode = this;
        this.childNodes.push(child);
      }
    },
    insertBefore(item, reference) {
      detach(item);
      item.parentNode = this;
      this.childNodes.splice(this.childNodes.indexOf(reference), 0, item);
      return item;
    },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    addEventListener(name, listener) { this.listeners[name] = listener; },
    removeEventListener() {},
    focus() { this.focusCount++; global.document.activeElement = this; },
    scrollIntoView(options) { this.scrollIntoViewCalls = (this.scrollIntoViewCalls || []).concat(options); },
    getBoundingClientRect() { return { top: 0, bottom: 0, left: 0, right: 0, width: 0, height: 0 }; },
    descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); },
    querySelector(selector) {
      const occurrence = selector.match(/^\[data-occurrence-id="(.+)"\]$/);
      return (occurrence && this.descendants().find(child => child.dataset.occurrenceId === occurrence[1])) || null;
    }
  });
  global.document = {
    activeElement: null,
    documentElement: element('html'),
    getElementById(id) { if (!roots.has(id)) roots.set(id, element('div')); return roots.get(id); },
    createElement(tagName) { counts.elements++; return element(tagName); },
    createTextNode(value) { counts.texts++; return text(value); },
    querySelectorAll(selector) {
      const all = [...roots.values()].flatMap(root => root.descendants());
      return selector === '[data-focus-target]' ? all.filter(node => node.dataset.focusTarget) : all;
    }
  };
  global.getComputedStyle = () => ({ getPropertyValue: () => '' });
  return { roots, counts };
}

function serialize(node) {
  if (node.nodeType === 3) return JSON.stringify(node.data);
  return '<' + [node.tagName, node.className, node.type, node.checked, node.value, node.selected, node.tabIndex,
    node.title, node.style.color, JSON.stringify(node.dataset), JSON.stringify(node.attributes)].join('|') + '>'
    + node.ownText + node.childNodes.map(serialize).join('') + '</>';
}

function reviewFixture(count, regroup = () => {}) {
  const { text, list } = detections(count);
  state.text = text;
  state.detections = list;
  state.types = ['PERSONA', 'DNI', 'EMAIL', 'DIRECCION'];
  state.entitySearch = '';
  state.entityTypeFilter = '';
  regroup(list);
  app.groupEntities();
}

function rowOf(entityKey) {
  return document.getElementById('entities').children.find(row => row.dataset.entityKey === entityKey);
}

function toggleFromCheckbox(entityKey) {
  const checkbox = rowOf(entityKey).children[0];
  checkbox.checked = !checkbox.checked;
  checkbox.focus();
  checkbox.listeners.change();
  return checkbox;
}

function assertMatchesFullRender() {
  const entities = document.getElementById('entities');
  const doctext = document.getElementById('doctext');
  const patched = serialize(entities) + serialize(doctext);
  app.renderEntities();
  app.renderDocument({ scrollActiveLocation: false });
  assert.equal(patched, serialize(entities) + serialize(doctext));
}

function cleanupTreeDom(t) {
  t.after(() => {
    delete global.document;
    delete global.getComputedStyle;
    state.comparing = false;
  });
}

test('a checkbox toggle patches only its entity row and document ranges, matching a full render', t => {
  cleanupTreeDom(t);
  const { roots, counts } = treeDom();
  // k5 also owns d105 and d205, so one toggle changes three separate document ranges.
  reviewFixture(300, list => { for (const index of [105, 205]) list[index].entityKey = 'k5'; });
  app.renderEntities();
  app.renderDocument();
  const rows = roots.get('entities').children;
  const nodes = [...roots.get('doctext').childNodes];
  counts.elements = 0;
  counts.texts = 0;

  const checkbox = toggleFromCheckbox('k5');

  assert.equal(state.rejected.has('k5'), true);
  const rowsAfter = roots.get('entities').children;
  assert.equal(rowsAfter.length, rows.length);
  rowsAfter.forEach((row, index) => assert.ok(row === rows[index], 'every entity row keeps its identity'));
  const row = rowOf('k5');
  assert.ok(row.children[0] === checkbox, 'the toggled checkbox is the same element');
  assert.equal(checkbox.checked, false);
  assert.equal(row.className, 'entity off');
  assert.match(checkbox.attributes['aria-label'], /^Se conserva Valor(5|105|205) en el texto; grupo de 3 apariciones/);
  assert.ok(row.children[1].children.some(child => child.textContent === 'Se conserva en el texto'));
  assert.ok(global.document.activeElement === checkbox, 'focus stays on the toggled checkbox');
  assert.equal(state.focusTarget, null);

  const after = roots.get('doctext').childNodes;
  assert.equal(after.filter(node => !nodes.includes(node)).length, 3, 'one merged text node per rejected occurrence');
  assert.equal(nodes.filter(node => !after.includes(node)).length, 9, 'only the three marks and their neighbours');
  assert.equal(counts.elements, 1, 'only the discarded note is created');
  assertMatchesFullRender();
});

test('re-checking restores live marks and keeps keyboard activation on recreated marks', t => {
  cleanupTreeDom(t);
  const { roots } = treeDom();
  reviewFixture(60);
  app.renderEntities();
  app.renderDocument();
  const rows = roots.get('entities').children;
  toggleFromCheckbox('k7');
  toggleFromCheckbox('k7');

  assert.equal(state.rejected.has('k7'), false);
  roots.get('entities').children.forEach((row, index) => assert.ok(row === rows[index], 'entity row ' + index + ' keeps its identity'));
  assert.equal(rowOf('k7').className, 'entity');
  assert.equal(rowOf('k7').children[1].children.some(child => child.textContent === 'Se conserva en el texto'), false);
  const recreated = roots.get('doctext').children.find(mark => mark.textContent === 'Valor7');
  recreated.listeners.keydown({ key: 'Enter', preventDefault() {} });
  assert.equal(state.activeEntityKey, 'k7');
  assert.equal(state.activeOccurrenceId, 'd7');
  assertMatchesFullRender();
});

test('toggling the active nested entity exposes the inner mark, keeps its location marker and scrolls as before', t => {
  cleanupTreeDom(t);
  const { roots } = treeDom();
  reviewFixture(40, list => {
    // An outer address spanning Valor10 Valor11 hides those inner detections until it is rejected.
    list.push({ id: 'outer', type: 'DIRECCION', start: list[10].start, end: list[11].end, entityKey: 'address',
      provenance: 'SOURCE', confidence: 0.9 });
  });
  app.selectEntityLocation('address');
  app.renderEntities();
  app.renderDocument();
  const rows = roots.get('entities').children;
  const location = () => roots.get('doctext').querySelector('[data-occurrence-id="outer"]');
  assert.equal(location().className, 'hl active-location');

  toggleFromCheckbox('address');

  roots.get('entities').children.forEach((row, index) => assert.ok(row === rows[index], 'entity row ' + index + ' keeps its identity'));
  // The exposed inner marks carry the location; the rejected gap between them becomes a marker.
  const located = roots.get('doctext').children.filter(node => node.dataset.occurrenceId === 'outer');
  assert.deepEqual(located.map(node => [node.className, node.textContent]), [
    ['hl active-location', 'Valor10'], ['location-marker active-location', ' '], ['hl active-location', 'Valor11']]);
  assert.deepEqual(location().scrollIntoViewCalls, [{ block: 'center', inline: 'nearest', behavior: 'smooth' }]);
  assertMatchesFullRender();
});

test('comparison mode falls back to the full render with identical output', t => {
  cleanupTreeDom(t);
  treeDom();
  reviewFixture(30);
  state.comparing = true;
  app.renderEntities();
  app.renderDocument();
  toggleFromCheckbox('k3');
  assert.equal(state.rejected.has('k3'), true);
  assert.ok(document.getElementById('doctext').children.some(mark => mark.textContent === 'Valor3'),
    'comparison keeps the preserved range visible');
  assertMatchesFullRender();
});

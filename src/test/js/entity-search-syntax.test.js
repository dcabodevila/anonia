'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const app = require('../../main/resources/web/app.js');
const { state } = app;

const VALUES = ['Mariana', 'Ana', 'Anabel', 'a*b', 'axb', 'a.c', 'abc', 'x+y', 'f(1)', 'c[0]', 'p|q', '$5', '^up', 'back\\slash', 'a%b'];

function fixture(values, type = 'PERSONA') {
  let text = '';
  const detections = [];
  values.forEach((value, i) => {
    const start = text.length;
    text += value + ' / ';
    detections.push({ id: 'd' + i, type: typeof type === 'function' ? type(value) : type,
      start, end: start + value.length, entityKey: 'k' + i, provenance: 'test', confidence: 1 });
  });
  state.text = text;
  state.detections = detections;
  state.types = ['PERSONA', 'EMAIL'];
  state.edits = [];
  state.rejected = new Set();
  app.groupEntities();
}

function search(query, type = '') {
  app.setEntityFilters(query, type);
  return app.filteredEntities().map(([, entity]) => entity.best);
}

beforeEach(() => {
  state.entitySearch = '';
  state.entityTypeFilter = '';
  fixture(VALUES);
});

test('S2a plain text stays a case-insensitive substring match', () => {
  assert.deepEqual(search('ANA').sort(), ['Ana', 'Anabel', 'Mariana']);
  assert.deepEqual(search(''), app.filteredEntities().map(([, e]) => e.best));
  assert.equal(search('').length, VALUES.length);
});

test('S2b * and % are anchored wildcards: prefix, suffix, contains, middle', () => {
  assert.deepEqual(search('ana*').sort(), ['Ana', 'Anabel']);
  assert.deepEqual(search('ana%').sort(), ['Ana', 'Anabel']);
  assert.deepEqual(search('*ana').sort(), ['Ana', 'Mariana']);
  assert.deepEqual(search('*ana*').sort(), ['Ana', 'Anabel', 'Mariana']);
  assert.deepEqual(search('%ana%').sort(), ['Ana', 'Anabel', 'Mariana']);
  assert.deepEqual(search('a*c').sort(), ['a.c', 'abc']);
  assert.deepEqual(search('a%c').sort(), ['a.c', 'abc']);
  assert.equal(search('*').length, VALUES.length);
});

test('S2c ? and _ match exactly one character and anchor the pattern', () => {
  assert.deepEqual(search('a?c').sort(), ['a.c', 'abc']);
  assert.deepEqual(search('a_c').sort(), ['a.c', 'abc']);
  assert.deepEqual(search('an_'), ['Ana']);
  assert.deepEqual(search('a??').sort(), ['Ana', 'a%b', 'a*b', 'a.c', 'abc', 'axb']);
});

test('S2d quoted query is a case-insensitive exact match with literal characters', () => {
  assert.deepEqual(search('"ana"'), ['Ana']);
  assert.deepEqual(search('"ANA"'), ['Ana']);
  assert.deepEqual(search('"a*b"'), ['a*b']);
  assert.deepEqual(search('"a%b"'), ['a%b']);
  assert.deepEqual(search('"a?c"'), []);
  assert.deepEqual(search('"an"'), []);
});

test('S2d an unclosed quote is literal text', () => {
  assert.deepEqual(search('"ana'), []);
  fixture(['"ana club', 'ana']);
  assert.deepEqual(search('"ana'), ['"ana club']);
  assert.deepEqual(search('ana"'), []);
});

test('S2e regex metacharacters are literal and never throw', () => {
  for (const [query, expected] of [['a.c', ['a.c']], ['x+y', ['x+y']], ['f(1)', ['f(1)']], ['c[0]', ['c[0]']],
    ['p|q', ['p|q']], ['$5', ['$5']], ['^up', ['^up']], ['back\\slash', ['back\\slash']]]) {
    assert.deepEqual(search(query), expected, query);
  }
  for (const query of ['(', '[', '\\', '*(', '+*', '?[', '"(', '(*', '[a-', '\\*']) {
    assert.doesNotThrow(() => search(query), query);
  }
  assert.deepEqual(search('a.*'), ['a.c']);
});

test('S2 wildcard search combines with the type filter', () => {
  fixture(['Ana', 'Anabel', 'ana@x.es'], value => value.includes('@') ? 'EMAIL' : 'PERSONA');
  assert.deepEqual(search('ana*', 'PERSONA').sort(), ['Ana', 'Anabel']);
  assert.deepEqual(search('ana*', 'EMAIL'), ['ana@x.es']);
  assert.deepEqual(search('"ana"', 'EMAIL'), []);
});

test('S2b/S2e hyphen wildcard queries filter without throwing', () => {
  fixture(['Ana-Maria', 'Anne-Marie', 'Ana', 'x-marie']);
  assert.deepEqual(search('ana-*'), ['Ana-Maria']);
  assert.deepEqual(search('anne-marie*'), ['Anne-Marie']);
  assert.deepEqual(search('%-marie').sort(), ['Anne-Marie', 'x-marie']);
});

test('S2e no ASCII punctuation char combined with * throws', () => {
  for (const ch of '!"#$%&\'()*+,-./:;<=>?@[\]^_`{|}~') {
    assert.doesNotThrow(() => search(ch + '*'), ch);
    assert.doesNotThrow(() => search('*' + ch + 'a'), ch);
  }
});

test('S2b/S2c pathological multi-wildcard queries on long near-matching values stay fast', () => {
  fixture(['a'.repeat(6000), 'a'.repeat(5999) + 'b']);
  for (const query of ['*a*a*a*a*a*a*a*a*b', '%a%a%a%a%a%a%a%a%b', '*a?a*a_a*a*a*a*a*c']) {
    const started = Date.now();
    const result = search(query);
    const elapsed = Date.now() - started;
    assert.ok(elapsed < 200, `${query} took ${elapsed} ms`);
    assert.ok(result.length <= 1);
  }
  assert.deepEqual(search('*a*a*a*a*a*a*a*a*b'), ['a'.repeat(5999) + 'b']);
  assert.deepEqual(search('*a*a*a*a*a*a*a*a*c'), []);
});

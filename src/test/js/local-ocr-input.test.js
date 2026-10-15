'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');

test('the document picker takes its accepted formats from the server-side format registry', () => {
  const html = fs.readFileSync('src/main/resources/web/index.html', 'utf8');

  assert.ok(html.includes('<input type="file" id="file" accept="{{DOCUMENT_ACCEPT}}" hidden>'));
});

test('the empty dropzone names every supported format family', () => {
  const html = fs.readFileSync('src/main/resources/web/index.html', 'utf8');

  assert.match(html, /id="drop-sub" class="drop-sub">PDF con capa de texto, Word, Excel, Markdown o TXT, o fotos JPEG\/PNG/);
});

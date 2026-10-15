'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');

test('the document picker accepts PDF, photos, Word, Excel, Markdown, and plain text uploads', () => {
  const html = fs.readFileSync('src/main/resources/web/index.html', 'utf8');

  assert.ok(html.includes('accept="application/pdf,image/jpeg,image/png,'
    + 'application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/msword,'
    + 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel,'
    + 'text/markdown,text/plain,.pdf,.jpg,.jpeg,.png,.docx,.doc,.xlsx,.xls,.md,.markdown,.txt"'));
});

test('the empty dropzone names every supported format family', () => {
  const html = fs.readFileSync('src/main/resources/web/index.html', 'utf8');

  assert.match(html, /id="drop-sub" class="drop-sub">PDF con capa de texto, Word, Excel, Markdown o TXT, o fotos JPEG\/PNG/);
});

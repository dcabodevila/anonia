'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(root, 'app.css'), 'utf8');

function loadingSection() {
  const match = html.match(/<div id="loading"[\s\S]*?<\/div>/);
  assert.ok(match, 'loading section missing');
  return match[0];
}

test('loading status shows a decorative spinner next to its announced text', () => {
  const section = loadingSection();
  assert.match(section, /role="status"/);
  assert.match(section, /<span class="loading-spinner" aria-hidden="true"><\/span>/);
  assert.match(section, /Analizando documento&hellip;/);
});

test('loading status renders inside the dropzone, where the user just dropped the file', () => {
  const dropzone = html.match(/<section id="dropzone"[\s\S]*?<\/section>/);
  assert.ok(dropzone, 'dropzone missing');
  assert.match(dropzone[0], /id="loading"/, 'the hero fills the viewport, so loading below it is off-screen');
  assert.match(css, /\.dropzone:has\(#loading:not\(\.hidden\)\)\s+\.drop-inner\s*\{[^}]*display:\s*none/);
});

test('spinner rotates only when the user has not asked for reduced motion', () => {
  assert.match(css, /\.loading-spinner\s*\{[^}]*border-radius:\s*50%/);
  const motion = css.match(/@media \(prefers-reduced-motion:\s*no-preference\)\s*\{[\s\S]*?\.loading-spinner\s*\{[^}]*animation:\s*loading-spin[^}]*\}/);
  assert.ok(motion, 'spinner animation must be guarded by prefers-reduced-motion');
  assert.match(css, /@keyframes loading-spin\s*\{[^}]*rotate\(360deg\)/);
});

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

function rule(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = css.match(new RegExp(String.raw`${escaped}\s*\{([^}]*)\}`));
  assert.ok(match, `${selector} rule missing`);
  return match[1];
}

function px(declarations, property) {
  const match = declarations.match(new RegExp(String.raw`(?:^|;|\s)${property}:\s*(\d+)px`));
  assert.ok(match, `${property} in px missing`);
  return Number(match[1]);
}

test('loading status shows a decorative sparkle, a playful title and a plain description', () => {
  const section = loadingSection();
  assert.match(section, /role="status"/);
  assert.match(section, /<svg class="loading-mark"[^>]*aria-hidden="true"/);
  assert.match(section, /<span class="loading-title">Anonimusing&hellip;<\/span>/);
  assert.match(section, /<span class="loading-sub">Analizando documento&hellip;<\/span>/);
});

test('loading status renders inside the dropzone, where the user just dropped the file', () => {
  const dropzone = html.match(/<section id="dropzone"[\s\S]*?<\/section>/);
  assert.ok(dropzone, 'dropzone missing');
  assert.match(dropzone[0], /id="loading"/, 'the hero fills the viewport, so loading below it is off-screen');
  assert.match(css, /\.dropzone:has\(#loading:not\(\.hidden\)\)\s+\.drop-inner\s*\{[^}]*display:\s*none/);
});

test('loading status is large: stacked layout, big sparkle and headline-sized title', () => {
  assert.match(rule('.dropzone .loading'), /flex-direction:\s*column/);
  assert.ok(px(rule('.loading-mark'), 'width') >= 56, 'sparkle should be at least 56px');
  assert.ok(px(rule('.loading-title'), 'font-size') >= 28, 'title should be at least 28px');
});

test('sparkle and title shimmer animate only when the user has not asked for reduced motion', () => {
  const motion = css.match(/@media \(prefers-reduced-motion:\s*no-preference\)\s*\{([\s\S]*?)\n\}/);
  assert.ok(motion, 'reduced-motion guard missing');
  assert.match(motion[1], /\.loading-mark\s*\{[^}]*animation:\s*loading-sparkle/);
  assert.match(motion[1], /\.loading-title\s*\{[^}]*animation:\s*loading-shimmer/);
  assert.match(motion[1], /@keyframes loading-sparkle\s*\{[\s\S]*?rotate\(/);
  assert.match(motion[1], /@keyframes loading-shimmer\s*\{[\s\S]*?background-position/);
});

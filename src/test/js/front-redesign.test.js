'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const webRoot = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(webRoot, 'app.css'), 'utf8');

test('Anonia keeps the entity menu small and the document dominant', () => {
  assert.match(html, /<h1>Anonia<\/h1>/);
  assert.match(html, /id="entities" class="entities"/);
  assert.match(html, /Anonimizar/);
  assert.doesNotMatch(html, /id="reset"|Aceptar todo/);
  assert.ok((html.match(/class="button-icon"/g) || []).length >= 7);
  assert.match(html, /drop-icon[\s\S]*Arrastra un PDF aqu&iacute; o/);
  assert.match(html, /id="browse" class="link" title="Selecciona un PDF"/);
  assert.match(html, /Podr&aacute;s visualizar los cambios a anonimizar antes de generar el Markdown\./);
  assert.doesNotMatch(html, /Aplicar y verificar|No tienes uno a mano|desidentificado|doc-anonymizer/);
  assert.doesNotMatch(html, /data-tab="checks"|id="tab-checks"|spinner|progress/);
  assert.match(css, /grid-template-columns:\s*minmax\(260px, 0\.85fr\)\s+minmax\(0, 1\.65fr\)/);
  assert.match(css, /\.pane-side\s*\{\s*position:\s*sticky/);
  assert.match(css, /\.pane-doc\s*\{[\s\S]*min-height:\s*clamp\(0px,\s*calc\(100dvh\s*-\s*220px\),\s*42rem\)/);
  assert.match(css, /\.doctext\s*\{[\s\S]*max-height:\s*none/);
  assert.match(css, /button\.primary, button\.ghost, button\.warning-action[\s\S]*width:\s*100%/);
});

test('verification feedback uses an accessible toast without a progress bar', () => {
  assert.match(html, /id="toast-region"[^>]*aria-live="polite"/);
  assert.doesNotMatch(html, /Verificaci&oacute;n/);
  assert.match(fs.readFileSync(path.join(webRoot, 'app.js'), 'utf8'), /showToast\(/);
  assert.doesNotMatch(css, /toast-progress|progress-bar/);
});

test('warning download stays hidden until a warning result exists', () => {
  assert.match(html, /id="warning-download" class="warning-action hidden"/);
  assert.match(fs.readFileSync(path.join(webRoot, 'app.js'), 'utf8'),
    /warning-download'[\s\S]*classList\.toggle\('hidden', !state\.warningDownloadEligible\)/);
});

test('comparison names both documents and keeps result controls in the anonymized header', () => {
  assert.match(html, /id="tab-document-control"[^>]*>[^<]*[\s\S]*Documento Original/);
  assert.match(html, /id="tab-result-control"[^>]*>[^<]*[\s\S]*Documento anonimizado/);
  assert.match(html, /<h2>Documento Original<\/h2>/);
  assert.match(html, /<div class="pane-head">[\s\S]*<h2>Documento anonimizado<\/h2>[\s\S]*id="result-actions"/);
  assert.match(html, /id="entity-controls"/);
  assert.match(css, /#result-actions\s*\{[\s\S]*flex-wrap:\s*wrap/);
  assert.match(css, /\.pane-main\.comparing\s+\.doctext,[\s\S]*\.pane-main\.comparing\s+\.markdown[\s\S]*overflow:\s*visible/);
  assert.match(css, /\.pane-side\.comparing[\s\S]*inline-size:/);
});

test('the workspace keeps Entidades fixed and exposes accessible main Documento and Resultado tabs with compare', () => {
  assert.match(html, /<aside class="pane pane-side"[^>]*aria-label="Entidades a anonimizar"/);
  assert.doesNotMatch(html, /data-tab="entities"|id="tab-entities"/);
  assert.match(html, /<nav class="tabs" role="tablist" aria-label="Vista principal">/);
  assert.match(html, /id="tab-document-control"[^>]*role="tab"[^>]*aria-controls="tab-document"/);
  assert.match(html, /id="tab-result-control"[^>]*role="tab"[^>]*aria-controls="tab-result"/);
  assert.match(html, /id="compare"[^>]*aria-pressed="false"/);
  assert.match(html, /id="tab-document"[^>]*role="tabpanel"[^>]*aria-labelledby="tab-document-control"/);
  assert.match(html, /id="tab-result"[^>]*role="tabpanel"[^>]*aria-labelledby="tab-result-control"/);
  assert.match(css, /\.pane-main\.comparing\s+\.main-tabpanels\s*\{[\s\S]*grid-template-columns:/);
  assert.match(css, /@media \(max-width: 850px\)[\s\S]*\.pane-main\.comparing\s+\.main-tabpanels\s*\{[\s\S]*grid-template-columns:\s*1fr/);
});

test('viewport hardening keeps scroll regions usable and compact controls from overflowing', () => {
  assert.match(css, /\.doctext\s*\{[\s\S]*height:\s*clamp\(12rem,\s*calc\(100dvh\s*-\s*280px\),\s*62rem\)/);
  assert.match(css, /\.entities\s*\{[\s\S]*max-height:\s*clamp\(10rem,\s*calc\(100dvh\s*-\s*525px\),\s*34rem\)/);
  assert.match(css, /\.pane:focus-within\s*\{[\s\S]*overflow:\s*visible/);
  assert.match(css, /@media \(max-width: 680px\)[\s\S]*\.topbar\s*\{[\s\S]*flex-wrap:\s*wrap/);
  assert.match(css, /@media \(max-width: 680px\)[\s\S]*\.main-tabbar\s*\{[\s\S]*flex-wrap:\s*wrap/);
  assert.match(css, /@media \(max-width: 680px\)[\s\S]*\.entity-filters\s*\{[\s\S]*grid-template-columns:\s*1fr/);
  assert.match(css, /@media \(max-height: 700px\) and \(min-width: 1101px\)[\s\S]*\.pane-side\s*\{[\s\S]*position:\s*static/);
});

test('successful analysis has a compact replacement affordance and mobile reading order leads with the main pane', () => {
  assert.match(html, /id="dropzone" class="dropzone"/);
  assert.match(html, /id="drop-title"/);
  assert.match(css, /\.dropzone\.compact\s*\{/);
  assert.ok(html.indexOf('id="pane-main"') < html.indexOf('id="pane-side"'));
  assert.match(css, /\.pane-main\s*\{[\s\S]*grid-column:\s*2/);
  assert.match(css, /@media \(max-width: 1100px\)[\s\S]*\.pane-main\s*\{[\s\S]*grid-row:\s*1/);
  assert.match(css, /@media \(max-width: 1100px\)[\s\S]*\.pane-side\s*\{[\s\S]*grid-row:\s*2/);
  assert.doesNotMatch(css, /@media \(max-width: 600px\)[\s\S]*\.entities\s*\{\s*max-height:\s*none/);
});

test('comparison keeps result actions in the anonymized document header and aligns desktop header tracks', () => {
  const resultPanel = html.match(/<section class="pane tabpanel hidden" id="tab-result"[\s\S]*?<\/section>/)[0];
  assert.match(resultPanel, /<div class="pane-head">[\s\S]*<h2>Documento anonimizado<\/h2>[\s\S]*id="result-actions"/);
  assert.match(html, /<\/div>\s*<section id="result-status"[\s\S]*<div class="main-tabpanels">/);
  assert.match(css, /@media \(min-width: 851px\)[\s\S]*\.pane-main\.comparing\s+\.pane-head\s*\{[\s\S]*min-block-size:\s*58px/);
  assert.match(css, /\.doctext\s*\{[\s\S]*font:\s*13px\/1\.75/);
  assert.match(css, /\.pane-main\.comparing\s+\.markdown\s*\{[\s\S]*font:\s*13px\/1\.75/);
});

test('mobile result actions wrap whole readable buttons and success toasts stay below uploads', () => {
  assert.match(css, /@media \(max-width: 600px\)[\s\S]*#result-actions button\s*\{[\s\S]*flex:\s*1 1 152px/);
  assert.match(css, /@media \(max-width: 600px\)[\s\S]*\.toast-region\s*\{[\s\S]*bottom:\s*max\(14px, env\(safe-area-inset-bottom\)\)/);
  assert.match(css, /@media \(max-width: 600px\)[\s\S]*\.toast-region\s*\{[\s\S]*max-height:\s*min\(40dvh, 18rem\)/);
});

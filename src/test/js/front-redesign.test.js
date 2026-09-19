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
  assert.match(css, /\.pane-doc\s*\{[\s\S]*min-height:\s*calc\(100vh\s*-\s*220px\)/);
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

test('comparison names both documents and keeps compact result actions in its header', () => {
  assert.match(html, /id="tab-document-control"[^>]*>[^<]*[\s\S]*Documento Original/);
  assert.match(html, /id="tab-result-control"[^>]*>[^<]*[\s\S]*Documento anonimizado/);
  assert.match(html, /<h2>Documento Original<\/h2>/);
  assert.match(html, /<div class="pane-head result-head">[\s\S]*<h2>Documento anonimizado<\/h2>[\s\S]*id="result-actions"/);
  assert.match(html, /id="entity-controls"/);
  assert.match(css, /\.result-head\s*\{[\s\S]*align-items:\s*center/);
  assert.match(css, /#tab-result \.actions\s*\{[\s\S]*flex-wrap:\s*wrap/);
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

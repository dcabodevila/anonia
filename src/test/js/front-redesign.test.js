'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const webRoot = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(webRoot, 'app.css'), 'utf8');

test('anonimuse keeps the entity menu small and the document dominant', () => {
  assert.match(html, /<title>anonimuse<\/title>/);
  assert.match(html, /<h1 class="brand-lockup">/);
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

test('entity sidebar nests compact stats under controls and removes global location chrome', () => {
  const controls = html.match(/<div class="side-content" id="entity-controls">([\s\S]*?)<\/div>\s*<\/aside>/)[1];
  assert.match(controls, /^\s*<div class="stats" id="stats"><\/div>\s*<div class="actions">/);
  assert.doesNotMatch(html, /class="entity-guide"|class="location-toolbar"|previous-occurrence|next-occurrence/);
  assert.match(html, /id="location-status"[^>]*role="status"[^>]*aria-live="polite"/);
  assert.doesNotMatch(html, /<div class="stats" id="stats"><\/div>\s*<div class="panes">/);
  assert.doesNotMatch(css, /\.entity-guide|\.location-toolbar|\.location-actions/);
  assert.match(css, /#entity-controls\s+\.stats\s*\{[\s\S]*grid-template-columns:\s*repeat\(4, minmax\(0, 1fr\)\)/);
  assert.match(css, /@media \(min-width: 1101px\)[^{]*\{[\s\S]*\.entity\s*\{[\s\S]*padding:\s*[67]px/);
  assert.match(css, /\.entity-meta\s*\{[^}]*font-size:\s*12px/);
});

test('brand assets provide an accessible lockup and responsive dropzone treatment', () => {
  assert.match(html, /<header class="topbar">[\s\S]*class="brand-lockup"[\s\S]*src="\/anonimuse-logo\.png"[\s\S]*src="\/anonimuse\.png"/);
  assert.match(html, /<img class="brand-symbol" src="\/anonimuse-logo\.png" width="604" height="557" alt="">/);
  assert.match(html, /<img class="brand-wordmark" src="\/anonimuse\.png" width="873" height="164" alt="anonimuse">/);
  assert.match(html, /id="dropzone"[\s\S]*<img class="drop-brand" src="\/anonimuse-logo\.png" width="604" height="557" alt="">/);
  assert.match(css, /--accent:\s*#[\da-f]{6}/i);
  assert.match(css, /--focus:/);
  assert.match(css, /--disabled:/);
  assert.match(css, /\.wordmark-backdrop\s*\{[\s\S]*background:\s*var\(--wordmark-backdrop\)/);
  assert.match(css, /\.dropzone\.compact\s+\.drop-brand\s*\{[\s\S]*display:\s*none/);
  assert.match(css, /@media \(max-width: 390px\)[\s\S]*\.brand-lockup/);
  assert.doesNotMatch(css, /--accent:\s*#(?:4a9eff|58a6ff|79c0ff)/i);
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
  assert.match(css, /\.doctext\s*\{[\s\S]*height:\s*clamp\(14rem,\s*calc\(100dvh\s*-\s*280px\),\s*62rem\)/);
  assert.match(css, /\.entities\s*\{[\s\S]*max-height:\s*clamp\(10rem,\s*calc\(100dvh\s*-\s*525px\),\s*34rem\)/);
  assert.match(css, /\.pane:focus-within\s*\{[\s\S]*overflow:\s*visible/);
  assert.match(css, /\.entity\s*\{[\s\S]*min-width:\s*0[\s\S]*max-width:\s*100%/);
  assert.match(css, /\.entity\s*>\s*\*\s*\{[\s\S]*min-width:\s*0/);
  assert.match(css, /select\.chip\s*\{[\s\S]*min-width:\s*0[\s\S]*max-width:\s*100%[\s\S]*flex-shrink:\s*1/);
  assert.match(css, /\.entity-locate\s*\{[\s\S]*min-inline-size:\s*44px/);
  assert.match(css, /#entity-controls \.stats\s*\{[\s\S]*max-inline-size:\s*100%/);
  assert.match(css, /@media \(max-width: 1100px\)[\s\S]*\.entity\s*\{[\s\S]*grid-template-columns:\s*44px\s+minmax\(0,\s*1fr\)\s+minmax\(0,\s*min\(128px,\s*100%\)\)/);
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
  assert.match(css, /\.doctext\s*\{[\s\S]*font:\s*16px\/1\.72/);
  assert.match(css, /\.pane-main\.comparing\s+\.markdown\s*\{[\s\S]*font:\s*15px\/1\.72/);
});

test('mobile result actions wrap whole readable buttons and success toasts stay below uploads', () => {
  assert.match(css, /@media \(max-width: 600px\)[\s\S]*#result-actions button\s*\{[\s\S]*flex:\s*1 1 152px/);
  assert.match(css, /@media \(max-width: 600px\)[\s\S]*\.toast-region\s*\{[\s\S]*bottom:\s*max\(14px, env\(safe-area-inset-bottom\)\)/);
  assert.match(css, /@media \(max-width: 600px\)[\s\S]*\.toast-region\s*\{[\s\S]*max-height:\s*min\(40dvh, 18rem\)/);
});

function contrastRatio(foreground, background) {
  const luminance = hex => {
    const channels = hex.match(/[\da-f]{2}/gi).map(value => parseInt(value, 16) / 255)
      .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
    return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
  };
  const [first, second] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (first + .05) / (second + .05);
}

test('disabled controls preserve semantic disabled styling with WCAG AA text contrast', () => {
  const disabled = css.match(/--disabled:\s*(#[\da-f]{6})/i)[1];
  const surface = css.match(/--disabled-surface:\s*(#[\da-f]{6})/i)[1];
  const rule = css.match(/button:disabled\s*\{([^}]+)\}/)[1];

  assert.ok(contrastRatio(disabled, surface) >= 4.5,
    'disabled text must meet 4.5:1 against its disabled surface');
  assert.match(rule, /opacity:\s*1(?:;|\s|$)/);
  assert.match(rule, /cursor:\s*not-allowed/);
  assert.match(rule, /border-color:\s*var\(--disabled\)/);
});

test('readable Gentle theme uses official roles, local type stacks, a focal hero, and accessible browser surfaces', () => {
  for (const [token, value] of Object.entries({
    base: '1a1218', 'surface-0': '20161e', 'surface-1': '241822', overlay: '342230',
    text: 'f6eff3', muted: 'a78e9b', accent: 'f095c8', pink: 'ffb1dd',
    success: 'b4e7c7', warning: 'e0c27a', error: 'ff718f'
  })) assert.match(css, new RegExp('--' + token + ':\\s*#' + value, 'i'));
  assert.match(css, /font:\s*16px\/1\.6\s+"Segoe UI Variable Text",\s*"Segoe UI",\s*system-ui/);
  assert.match(css, /"Cascadia Mono",\s*"SFMono-Regular",\s*Consolas,\s*"Liberation Mono",\s*monospace/);
  assert.match(css, /\.drop-brand\s*\{[\s\S]*width:\s*(?:12[89]|1[34]\d|152)px/);
  assert.match(css, /@media \(max-width: 600px\)[\s\S]*\.drop-brand\s*\{[\s\S]*width:\s*(?:10[89]|1[01]\d|12[0-8])px/);
  assert.match(css, /::selection/);
  assert.match(css, /::-webkit-scrollbar/);
  assert.match(css, /caret-color:\s*var\(--accent\)/);
  assert.match(html, /Pulsa cualquier marca para ubicarla y revisar la selección/);
});

test('inline entity editor fills its main track without inheriting checkbox sizing', () => {
  assert.match(css, /\.entity\s*>\s*input\s*\{[^}]*inline-size:\s*44px[^}]*block-size:\s*44px/);
  assert.doesNotMatch(css, /\.entity\s+input\s*\{/);

  const editor = css.match(/\.entity-editor\s*\{([^}]+)\}/)[1];
  assert.match(editor, /width:\s*100%/);
  assert.match(editor, /min-width:\s*0/);
  assert.match(editor, /min-height:\s*44px/);

  assert.match(css, /@media \(max-width: 1100px\)[\s\S]*\.entity\s*\{[\s\S]*grid-template-columns:\s*44px\s+minmax\(0,\s*1fr\)\s+minmax\(0,\s*min\(128px,\s*100%\)\)/);
  assert.match(css, /@media \(min-width: 1101px\) and \(pointer: fine\)[\s\S]*\.entity\s*\{[\s\S]*grid-template-columns:\s*36px\s+minmax\(0,\s*1fr\)\s+minmax\(92px,\s*auto\)[\s\S]*\.entity\s*>\s*input\s*\{[^}]*inline-size:\s*36px[^}]*block-size:\s*36px/);
});

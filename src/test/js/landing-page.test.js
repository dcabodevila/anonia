'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const webRoot = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(webRoot, 'app.css'), 'utf8');

test('hero wraps the existing dropzone with a hero copy block', () => {
  const heroMatch = html.match(/<section class="hero">([\s\S]*?)<\/section>\s*<section id="error"/);
  assert.ok(heroMatch, 'expected a <section class="hero"> wrapping the dropzone, right before #error');
  const hero = heroMatch[1];
  assert.match(hero, /<div class="hero-copy">[\s\S]*<\/div>/);
  assert.match(hero, /id="dropzone" class="dropzone"/);
  assert.match(hero, /id="file"/);
  assert.match(hero, /id="browse" class="link"/);
  assert.match(hero, /id="drop-title"/);
  assert.match(hero, /id="drop-sub" class="drop-sub"/);
  assert.match(hero, /class="drop-brand"/);
  assert.match(hero, /class="drop-icon"/);
});

test('hero copy has a headline, subtitle and three trust pills', () => {
  const copyMatch = html.match(/<div class="hero-copy">([\s\S]*?)<\/div>\s*<section id="dropzone"/);
  assert.ok(copyMatch, 'expected hero-copy block right before the dropzone section');
  const copy = copyMatch[1];
  assert.match(copy, /Quita los datos personales de tus documentos antes de compartirlos con la IA/);
  assert.match(copy, /class="hero-pills"/);
  assert.match(copy, /Sin guardar nada/);
  assert.match(copy, /Sin IA/);
  assert.match(copy, /Sin conexi&oacute;n|Sin conexión/);
});

test('landing section sits after the workspace so error/loading stay right below the hero', () => {
  assert.ok(html.indexOf('id="workspace"') < html.indexOf('id="landing"'));
  assert.match(html, /<section id="landing" class="landing" aria-labelledby="[^"]+">/);
  assert.ok(html.indexOf('</section>\n</main>') !== -1 || /id="landing"[\s\S]*<\/section>\s*<\/main>/.test(html));
});

test('landing hides while a document is loading or open, via CSS :has only', () => {
  assert.match(css, /main:has\(#workspace:not\(\.hidden\)\)\s+#landing[^{]*\{[\s\S]*display:\s*none/);
  assert.match(css, /main:has\(#loading:not\(\.hidden\)\)\s+#landing[^{]*\{[\s\S]*display:\s*none/);
});

test('hero copy disappears and the hero collapses to one column once the dropzone is compact', () => {
  assert.match(css, /\.hero:has\(\.dropzone\.compact\)\s+\.hero-copy\s*\{[\s\S]*display:\s*none/);
  assert.match(css, /\.hero:has\(\.dropzone\.compact\)\s*\{[\s\S]*grid-template-columns:\s*1fr/);
});

test('landing features the four workflow gifs with descriptive alt text and lazy loading', () => {
  for (const name of ['detect', 'review', 'compare', 'export']) {
    const figureRe = new RegExp(
      `<figure[^>]*>[\\s\\S]*?<img src="/landing/${name}\\.gif" width="960" height="570" loading="lazy" alt="[^"]{10,}">[\\s\\S]*?<figcaption>`);
    assert.match(html, figureRe, `expected a framed figure for ${name}.gif`);
  }
});

test('landing includes the three risk-free cards with the expected copy', () => {
  assert.match(html, /No guarda informaci[oó]n/);
  assert.match(html, /[Ee]l documento se procesa en memoria y no se conserva/);
  assert.match(html, /No usa IA para anonimizar/);
  assert.match(html, /[Rr]eglas y patrones deterministas, locales/);
  assert.match(html, /Funciona sin conexi[oó]n/);
  assert.match(html, /[Ss]e ejecuta en tu equipo, sin enviar nada a internet/);
  assert.ok((html.match(/class="button-icon"/g) || []).length >= 10, 'expects landing risk cards to reuse the stroke-style icon class');
});

test('landing closes with a call to action that jumps back to the dropzone', () => {
  assert.match(html, /<a class="cta" href="#dropzone">Anonimizar un documento<\/a>/);
});

test('landing has no external network resources: the app runs fully offline', () => {
  assert.doesNotMatch(html, /https?:\/\//);
  assert.doesNotMatch(css, /https?:\/\//);
  assert.doesNotMatch(html, /fonts\.googleapis|cdn\./);
});

test('landing layout stays responsive with a bounded max width and fluid images', () => {
  assert.match(css, /\.landing\s*\{[^}]*max-width:\s*118[0-9]px/);
  assert.match(css, /\.landing[\s\S]*img\s*\{[^}]*max-width:\s*100%[^}]*height:\s*auto/);
});

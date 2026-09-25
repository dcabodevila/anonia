'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const webRoot = path.resolve(__dirname, '../../main/resources/web');
const html = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8');
const css = fs.readFileSync(path.join(webRoot, 'app.css'), 'utf8');

test('hero has a copy column and a hero-try column that wraps the existing dropzone', () => {
  const heroMatch = html.match(/<section class="hero">([\s\S]*?)<\/section>\s*<section id="error"/);
  assert.ok(heroMatch, 'expected a <section class="hero"> right before #error');
  const hero = heroMatch[1];
  assert.match(hero, /<div class="hero-copy">[\s\S]*<\/div>/);
  assert.match(hero, /<div class="hero-try">[\s\S]*<\/div>/);
  assert.match(hero, /id="dropzone" class="dropzone"/);
  assert.match(hero, /id="file"/);
  assert.match(hero, /id="browse" class="link"/);
  assert.match(hero, /id="drop-title"/);
  assert.match(hero, /id="drop-sub" class="drop-sub"/);
  assert.match(hero, /class="drop-brand"/);
  assert.match(hero, /class="drop-icon"/);
});

test('hero copy has a big headline, a lede and no eyebrow/kicker label or pills', () => {
  const copyMatch = html.match(/<div class="hero-copy">([\s\S]*?)<\/div>\s*<div class="hero-try">/);
  assert.ok(copyMatch, 'expected hero-copy block right before hero-try');
  const copy = copyMatch[1];
  assert.match(copy, /class="hero-headline"/);
  assert.match(copy, /Quita los datos personales de tus documentos antes de compartirlos con la IA/);
  assert.match(copy, /class="hero-sub"/);
  assert.doesNotMatch(html, /hero-pill/);
  assert.doesNotMatch(html, /hero-eyebrow|hero-kicker/i);
  assert.match(css, /\.hero-headline\s*\{[^}]*font-size:\s*clamp\(2\.4rem,\s*5vw,\s*4\.25rem\)[^}]*font-weight:\s*750[^}]*letter-spacing:\s*-0\.025em[^}]*text-wrap:\s*balance/);
  assert.match(css, /\.hero-sub\s*\{[^}]*max-width:\s*58ch/);
});

test('hero has a primary download path and a secondary try-on-the-web path', () => {
  assert.match(html, /<a class="download-cta" href="https:\/\/github\.com\/dcabodevila\/anonia\/releases\/latest" rel="noopener">/);
  assert.match(html, /Descargar para Windows/);
  assert.match(html, /Instalador gratuito.{1,3}última versión en GitHub/);
  assert.match(html, /Para documentos reales: se ejecuta en tu equipo y el archivo nunca sale de él\./);

  const tryMatch = html.match(/<div class="hero-try">([\s\S]*?)<\/div>\s*<\/section>/);
  assert.ok(tryMatch, 'expected hero-try to close right before the hero section closes');
  const tryBlock = tryMatch[1];
  assert.match(tryBlock, /id="dropzone" class="dropzone"/);
  // The web-trial heading and privacy note live inside the drop box, centered with its content.
  const dropInner = html.match(/<div class="drop-inner">([\s\S]*?)<\/div>\s*<\/section>/)[1];
  assert.match(dropInner, /<h3 class="hero-try-heading">Pruébalo en la web<\/h3>/);
  assert.match(dropInner, /<p class="hero-try-note">Ideal para documentos de ejemplo: en la versión web el archivo se envía al servidor para analizarlo, no lo guardamos ni se lo enviamos a ninguna IA\.<\/p>/);
  assert.match(dropInner, /hero-try-note[\s\S]*id="drop-title"/);

  const downloadCtaMatch = css.match(/\.download-cta\s*\{([^}]*)\}/);
  assert.ok(downloadCtaMatch, 'expected a .download-cta rule');
  const downloadCtaRule = downloadCtaMatch[1];
  assert.match(downloadCtaRule, /background:\s*var\(--accent\)/);
  assert.match(downloadCtaRule, /color:\s*var\(--accent-ink\)/);
  assert.match(downloadCtaRule, /border-radius:\s*12px/);
  assert.match(downloadCtaRule, /min-height:\s*56px/);
  assert.match(css, /\.download-cta:hover\s*\{[^}]*background:\s*var\(--accent-hover\)/);
});

test('hero fills the first viewport on wide screens with equal-size stretched columns', () => {
  assert.match(css, /\.hero\s*\{[^}]*min-block-size:\s*calc\(100dvh[^}]*\)/);
  assert.match(css, /\.hero\s*\{[^}]*grid-template-columns:\s*1fr\s+1fr/);
  assert.match(css, /\.hero\s*\{[^}]*align-items:\s*stretch/);
});

test('the dropzone grows to fill the hero-try column and centers its content vertically', () => {
  assert.match(css, /#dropzone\s*\{[^}]*flex:\s*1/);
  assert.match(css, /#dropzone\s*\{[^}]*display:\s*flex/);
  assert.match(css, /#dropzone\s*\{[^}]*flex-direction:\s*column/);
  assert.match(css, /#dropzone\s*\{[^}]*justify-content:\s*center/);
});

test('hero collapses to a compact single-column strip once the dropzone is compact', () => {
  assert.match(css, /\.hero:has\(\.dropzone\.compact\)\s*\{[^}]*grid-template-columns:\s*1fr/);
  // The compact strip must span the workspace width, not the landing container.
  assert.match(css, /\.hero:has\(\.dropzone\.compact\)\s*\{[^}]*max-width:\s*none/);
  assert.match(css, /\.hero:has\(\.dropzone\.compact\)\s*\{[^}]*padding:\s*0/);
  assert.match(css, /\.hero:has\(\.dropzone\.compact\)\s+\.hero-copy\s*\{[^}]*display:\s*none/);
  assert.match(css, /\.dropzone\.compact\s+\.hero-try-heading,\s*\.dropzone\.compact\s+\.hero-try-note\s*\{[^}]*display:\s*none/);
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

test('each feature section pairs a heading+paragraph row above a full-width framed screenshot', () => {
  for (const name of ['detect', 'review', 'compare', 'export', 'ai']) {
    const figureRe = new RegExp(
      `<figure[^>]*>[\\s\\S]*?<img src="/landing/${name}\\.gif" width="1280" height="760" loading="lazy" alt="[^"]{10,}">[\\s\\S]*?<figcaption>`);
    assert.match(html, figureRe, `expected a framed figure for ${name}.gif at 1280x760`);
  }
  assert.match(css, /\.landing-feature-copy\s*\{[^}]*grid-template-columns:\s*minmax\(0,1fr\)\s+minmax\(0,1fr\)/);
  assert.match(css, /\.landing-feature-heading h3\s*\{[^}]*font-size:\s*clamp\(1\.75rem,3\.2vw,2\.6rem\)/);
  assert.match(css, /\.landing-feature-copy p\s*\{[^}]*max-width:\s*60ch/);
  assert.match(css, /\.landing-feature\s*\{[^}]*margin-bottom:\s*clamp\(5rem,12vh,9rem\)/);
  assert.match(css, /\.landing-shot\s*\{[^}]*border-radius:\s*14px[^}]*box-shadow:\s*0 24px 60px -20px rgba\(0,0,0,\.6\)/);
});

test('a fifth feature section explains pasting the anonymized markdown into an AI assistant', () => {
  const stepMatch = html.match(/<span class="landing-step">5<\/span>\s*<h3>([^<]+)<\/h3>/);
  assert.ok(stepMatch, 'expected a step 5 heading');
  assert.equal(stepMatch[1], 'Pégalo en tu IA de confianza');
  assert.match(html, /Copia el Markdown anonimizado y pégalo en ChatGPT o en tu asistente habitual: la IA trabaja con etiquetas como \[PERSONA_001\] en lugar de tus datos reales\./);
  assert.match(html, /<figcaption>El texto anonimizado, listo en ChatGPT<\/figcaption>/);
});

test('landing replaces the risk cards with a web-vs-desktop comparison and a guarantees strip', () => {
  assert.match(html, /Prueba en la web, trabaja en tu equipo/);
  assert.match(html, /Versión web \(demo\)/);
  assert.match(html, /App de escritorio \(recomendada\)/);
  assert.match(html, /<dt>Dónde se procesa<\/dt><dd>En el servidor de la demo<\/dd>/);
  assert.match(html, /<dt>Dónde se procesa<\/dt><dd>En tu equipo<\/dd>/);
  assert.match(html, /<dt>Qué documentos<\/dt><dd>Ejemplos o documentos no sensibles<\/dd>/);
  assert.match(html, /<dt>Qué documentos<\/dt><dd>Cualquier documento, incluidos los sensibles<\/dd>/);
  assert.match(html, /<dt>Conexión<\/dt><dd>Necesita internet<\/dd>/);
  assert.match(html, /<dt>Conexión<\/dt><dd>Funciona sin conexión<\/dd>/);
  assert.match(css, /\.landing-compare-col-recommended\s*\{[^}]*outline:\s*1px solid var\(--accent\)/);
  assert.doesNotMatch(css, /\.landing-compare-col-recommended\s*\{[^}]*border-left:\s*[3-9]px/);

  assert.match(html, /No guarda información/);
  assert.match(html, /No usa IA para anonimizar/);
  assert.match(html, /Funciona sin conexión en tu equipo/);
  assert.ok((html.match(/class="button-icon"/g) || []).length >= 10, 'expects the guarantees strip to reuse the stroke-style icon class');
});

test('landing closes with two calls to action: try a sample and download the app', () => {
  // Trying the web demo is the secondary path; downloading stays primary.
  assert.match(html, /<a class="cta cta-secondary" href="#dropzone">Probar con un documento de ejemplo<\/a>/);
  assert.match(css, /\.cta-secondary\s*\{[^}]*background:\s*transparent/);
  assert.match(html, /<a class="cta" href="https:\/\/github\.com\/dcabodevila\/anonia\/releases\/latest" rel="noopener">Descargar para Windows<\/a>/);
});

test('the only external URL in index.html is the GitHub release link, used only in <a href>', () => {
  const urlMatches = html.match(/https?:\/\/[^\s"'<>]+/g) || [];
  assert.ok(urlMatches.length > 0, 'expected the release URL to appear at least once');
  for (const url of urlMatches) {
    assert.equal(url, 'https://github.com/dcabodevila/anonia/releases/latest');
  }
  const hrefMatches = html.match(/href="https:\/\/github\.com\/dcabodevila\/anonia\/releases\/latest"/g) || [];
  assert.equal(hrefMatches.length, urlMatches.length, 'the release URL must only ever appear inside an <a href>');
  assert.doesNotMatch(html, /<(?:script|link|img)[^>]*\s(?:src|href)="https?:\/\//);
  assert.doesNotMatch(css, /https?:\/\//);
  assert.doesNotMatch(html, /fonts\.googleapis|cdn\./);
});

test('landing layout stays responsive with a bounded max width and fluid images', () => {
  assert.match(css, /\.landing\s*\{[^}]*max-width:\s*118[0-9]px/);
  assert.match(css, /\.landing[\s\S]*img\s*\{[^}]*max-width:\s*100%[^}]*height:\s*auto/);
});

test('feature reveals animate the whole section (text + figure) as it scrolls into view, gated by @supports and reduced motion', () => {
  const supportsMatch = css.match(/@supports \(animation-timeline:\s*view\(\)\)\s*\{([\s\S]*)$/);
  assert.ok(supportsMatch, 'expected an @supports (animation-timeline: view()) block');
  const supportsBlock = supportsMatch[1];
  assert.match(supportsBlock, /@media \(prefers-reduced-motion:\s*no-preference\)\s*\{/);
  assert.match(supportsBlock, /animation-timeline:\s*view\(\)/);

  // Text block (heading + paragraph) reveals first...
  assert.match(supportsBlock, /\.landing-feature-copy,\s*\n\s*\.landing-compare,\s*\n\s*\.landing-cta\s*\{[^}]*animation-range:\s*entry 0% cover 30%/);
  // ...then the figure follows with a slight stagger.
  assert.match(supportsBlock, /\.landing-shot\s*\{[^}]*animation-range:\s*entry 10% cover 42%/);

  // Organic combination: fade, rise, settle, and un-blur.
  assert.match(supportsBlock, /opacity:\s*0;[\s\S]*?transform:\s*translateY\(64px\)\s*scale\(\.96\);[\s\S]*?filter:\s*blur\(10px\);/);
  assert.match(supportsBlock, /opacity:\s*1;[\s\S]*?transform:\s*translateY\(0\)\s*scale\(1\);[\s\S]*?filter:\s*blur\(0\);/);

  // The figure additionally reveals through a soft rounded clip-path.
  assert.match(supportsBlock, /clip-path:\s*inset\(12% 4% 0 4% round 24px\)/);
  assert.match(supportsBlock, /clip-path:\s*inset\(0 round 14px\)/);

  // The old heading-only redaction bar is gone.
  assert.doesNotMatch(css, /landing-heading-bar/);
  assert.doesNotMatch(css, /landing-feature-heading h3::after/);
});

test('hero headline and download CTA animate once on load, gated by reduced motion', () => {
  assert.match(css, /@media \(prefers-reduced-motion:\s*no-preference\)\s*\{[\s\S]*?\.hero-headline,\s*\.download-cta\s*\{[^}]*animation:\s*hero-rise 6[05]0ms/);
});

test('smooth scroll is gated by reduced motion and the dropzone has scroll margin', () => {
  assert.match(css, /@media \(prefers-reduced-motion:\s*no-preference\)\s*\{\s*html\s*\{\s*scroll-behavior:\s*smooth/);
  assert.match(css, /#dropzone\s*\{[^}]*scroll-margin-top:\s*24px/);
});

test('download CTA goes full width on narrow viewports', () => {
  assert.match(css, /@media \(max-width:900px\)\s*\{[\s\S]*\.download-cta\s*\{[^}]*width:\s*100%/);
});

test('the web vs desktop comparison is a top-level landing section in the heading outline', () => {
  assert.match(html, /<h2 class="landing-compare-heading">Prueba en la web, trabaja en tu equipo<\/h2>/);
  assert.match(html, /<h3>Versión web \(demo\)<\/h3>/);
  assert.match(html, /<h3>App de escritorio \(recomendada\)<\/h3>/);
  assert.doesNotMatch(html, /<h4>/);
  assert.doesNotMatch(css, /\.landing-compare-col-recommended \.landing-compare-row dt\s*\{[^}]*#[\da-f]{3,6}/i);
});

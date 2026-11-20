const { test, expect } = require('@playwright/test');
const fs = require('node:fs/promises');
const path = require('node:path');
const { spawn } = require('node:child_process');

// Measures the real user journey with synthetic HR workbooks of increasing size
// (written by e2e/perf/HrWorkbookGenerator.java during global setup):
// upload -> analysis -> detection rendering -> mark/dismiss -> "Anonimizar" -> copy/download,
// plus the command line pipeline. Every run writes its metrics to test-results-perf/.
//
// Budgets: a click that blocks the main thread longer than INTERACTION_BUDGET_MS, or any
// single long task above LONG_TASK_BUDGET_MS, is the "the app freezes for a while" symptom.
const INTERACTION_BUDGET_MS = 200;
const LONG_TASK_BUDGET_MS = 1000;
const TOGGLES = 4;
const MARK_CLICKS = 3;

const root = path.resolve(__dirname, '..', '..');
const runtime = path.join(root, 'e2e', '.runtime');
const fixtures = path.join(runtime, 'hr-fixtures');
const results = path.join(root, 'e2e', 'test-results-perf');
const sizes = (process.env.HR_SIZES || '50,150,300').split(',').map(s => Number(s.trim()));

// Installed before any app script: records long tasks and the largest gap between frames.
function instrument() {
  window.__perf = { longTasks: [], maxFrameGap: 0 };
  window.__perfReset = () => { window.__perf = { longTasks: [], maxFrameGap: 0 }; };
  new PerformanceObserver(list => {
    for (const entry of list.getEntries()) {
      window.__perf.longTasks.push({ start: Math.round(entry.startTime), duration: Math.round(entry.duration) });
    }
  }).observe({ type: 'longtask', buffered: true });
  let last = performance.now();
  const frame = now => {
    window.__perf.maxFrameGap = Math.max(window.__perf.maxFrameGap, now - last);
    last = now;
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  // Keeps a copy of each API response: Playwright cannot read a body after the page
  // replaces the request context, and the analysis JSON is the oracle for the download.
  window.__api = {};
  const original = window.fetch.bind(window);
  window.fetch = async (input, init) => {
    const started = performance.now();
    const response = await original(input, init);
    const url = String(input && input.url || input);
    const name = url.includes('/api/analyze') ? 'analyze' : url.includes('/api/apply') ? 'apply' : null;
    if (name) {
      const body = await response.clone().text();
      window.__api[name] = { ms: Math.round(performance.now() - started), bytes: body.length, body };
    }
    return response;
  };
}

async function collect(page) {
  const perf = await page.evaluate(() => {
    const value = window.__perf;
    window.__perfReset();
    return value;
  });
  const durations = perf.longTasks.map(t => t.duration);
  return {
    longTasks: durations.length,
    longTaskTotalMs: durations.reduce((a, b) => a + b, 0),
    longestTaskMs: durations.length ? Math.max(...durations) : 0,
    maxFrameGapMs: Math.round(perf.maxFrameGap)
  };
}

// Clicks synchronously inside the page: the elapsed time is exactly how long the click
// handler (render included) blocks the main thread before the browser can paint again.
async function timedClick(page, selector, index) {
  return page.evaluate(([css, i]) => {
    const target = document.querySelectorAll(css)[i];
    if (!target) return null;
    const started = performance.now();
    target.click();
    return Math.round(performance.now() - started);
  }, [selector, index]);
}

// CPU profile of one click: self time per app.js function, to name the hot spot.
async function profileClick(page, selector, index) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Profiler.enable');
  await cdp.send('Profiler.setSamplingInterval', { interval: 200 });
  await cdp.send('Profiler.start');
  const clickMs = await timedClick(page, selector, index);
  const { profile } = await cdp.send('Profiler.stop');
  await cdp.detach();
  const interval = (profile.endTime - profile.startTime) / 1000 / Math.max(profile.samples.length, 1);
  const hits = new Map();
  for (const id of profile.samples) hits.set(id, (hits.get(id) || 0) + 1);
  const self = new Map();
  for (const node of profile.nodes) {
    const frame = node.callFrame;
    const name = `${frame.functionName || '(anonymous)'} ${frame.url.split('/').pop()}:${frame.lineNumber + 1}`;
    self.set(name, (self.get(name) || 0) + (hits.get(node.id) || 0) * interval);
  }
  const top = [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, 12)
    .map(([fn, ms]) => ({ fn, selfMs: Math.round(ms) }));
  return { clickMs, top };
}

function runCli(file) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const child = spawn('java', ['-Dfile.encoding=UTF-8', '-cp', path.join(root, 'target', 'doc-anonymizer.jar'),
      'com.docanonymizer.adapter.cli.Main', '--dry-run', file], { cwd: root, windowsHide: true });
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    child.once('error', reject);
    child.once('exit', code => resolve({ code, wallMs: Date.now() - started, output }));
  });
}

async function save(name, data) {
  await fs.mkdir(results, { recursive: true });
  await fs.writeFile(path.join(results, name), JSON.stringify(data, null, 2));
}

test.describe('HR workbooks: freeze diagnosis', () => {
  // Not serial: a size that breaks the budget must not hide the measurements of the others.

  for (const rows of sizes) {
    test(`web review journey stays responsive with ${rows} employees`, async ({ page }) => {
      const file = path.join(fixtures, `hr-${rows}.xlsx`);
      const { baseUrl } = JSON.parse(await fs.readFile(path.join(runtime, 'server.json'), 'utf8'));
      await page.addInitScript(instrument);
      await page.goto(baseUrl);
      await expect(page.locator('#dropzone')).toBeVisible();
      const metrics = { rows, file: path.basename(file) };

      // Upload -> server analysis -> detection rendering.
      await page.evaluate(() => window.__perfReset());
      const started = Date.now();
      await page.locator('#file').setInputFiles(file);
      await page.waitForFunction(() => window.__api.analyze, null, { timeout: 300_000, polling: 100 });
      const responseAt = Date.now();
      const analyzeCall = await page.evaluate(() => window.__api.analyze);
      const analysis = JSON.parse(analyzeCall.body);
      await expect(page.locator('#workspace')).toBeVisible();
      await expect(page.locator('#error')).toBeHidden();
      await expect(page.locator('#entities .entity').first()).toBeVisible();
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => setTimeout(resolve, 0))));
      const renderedAt = Date.now();
      metrics.analyze = {
        serverElapsedMs: analysis.elapsedMs,
        requestMs: analyzeCall.ms,
        renderAfterResponseMs: renderedAt - responseAt,
        uploadToInteractiveMs: renderedAt - started,
        responseKb: Math.round(analyzeCall.bytes / 1024),
        textChars: analysis.text.length,
        detections: analysis.detections.length,
        ...(await collect(page))
      };
      metrics.dom = await page.evaluate(() => ({
        marks: document.querySelectorAll('#doctext .hl').length,
        entityRows: document.querySelectorAll('#entities .entity').length,
        elements: document.getElementsByTagName('*').length
      }));

      // Mark/dismiss: uncheck then re-check entity groups; each click re-renders synchronously.
      const toggles = [];
      for (let i = 0; i < TOGGLES; i++) {
        toggles.push(await timedClick(page, '#entities .entity input[type=checkbox]', i));
        toggles.push(await timedClick(page, '#entities .entity input[type=checkbox]', i));
      }
      const marks = [];
      for (let i = 0; i < MARK_CLICKS; i++) {
        marks.push(await timedClick(page, '#doctext .hl', i * 7));
      }
      metrics.interactions = { toggleMs: toggles, markClickMs: marks, ...(await collect(page)) };
      metrics.toggleProfile = await profileClick(page, '#entities .entity input[type=checkbox]', 0);
      await timedClick(page, '#entities .entity input[type=checkbox]', 0);
      await collect(page);

      // "Anonimizar": server apply + verification, then result rendering.
      const applyStarted = Date.now();
      await page.locator('#apply').click();
      await page.waitForFunction(() => window.__api.apply, null, { timeout: 300_000, polling: 100 });
      const applyCall = await page.evaluate(() => window.__api.apply);
      await expect(page.locator('#result-status')).toContainText('Anonimización completada');
      await expect(page.locator('#download')).toBeEnabled();
      metrics.apply = {
        requestMs: applyCall.ms,
        totalMs: Date.now() - applyStarted,
        responseKb: Math.round(applyCall.bytes / 1024),
        ...(await collect(page))
      };

      // Copy and download of the anonymized Markdown.
      const copyStarted = Date.now();
      await page.locator('#copy').click();
      await expect(page.locator('#copy-label')).toHaveText('Copiado');
      const downloadStarted = Date.now();
      const download = page.waitForEvent('download');
      await page.locator('#download').click();
      const output = path.join(runtime, `hr-${rows}.anonymized.md`);
      await (await download).saveAs(output);
      metrics.delivery = { copyMs: downloadStarted - copyStarted, downloadMs: Date.now() - downloadStarted, ...(await collect(page)) };
      await save(`web-${rows}.json`, metrics);
      console.log(JSON.stringify(metrics));

      // Functional oracle: no detected DNI/NIE survives in the downloaded Markdown.
      const markdown = await fs.readFile(output, 'utf8');
      const ids = analysis.detections.filter(d => d.type === 'DNI' || d.type === 'NIE')
        .map(d => analysis.text.slice(d.start, d.end));
      expect(ids.length).toBeGreaterThan(0);
      for (const id of ids.slice(0, 50)) expect(markdown).not.toContain(id);

      // Freeze budgets.
      const worstClick = Math.max(...toggles, ...marks);
      const worstTask = Math.max(metrics.analyze.longestTaskMs, metrics.interactions.longestTaskMs,
        metrics.apply.longestTaskMs);
      expect.soft(worstClick, `worst click blocks the main thread ${worstClick} ms`).toBeLessThanOrEqual(INTERACTION_BUDGET_MS);
      expect.soft(worstTask, `longest main-thread task ${worstTask} ms`).toBeLessThanOrEqual(LONG_TASK_BUDGET_MS);
    });
  }

  test('command line pipeline timings for every size', async () => {
    const cli = [];
    for (const rows of sizes) {
      const run = await runCli(path.join(fixtures, `hr-${rows}.xlsx`));
      const elapsed = /Tiempo[ .]+(\d+) ms/.exec(run.output);
      const blocked = run.output.split(/\r?\n/).filter(line => line.includes('[C')).length;
      cli.push({ rows, exitCode: run.code, wallMs: run.wallMs, pipelineMs: elapsed ? Number(elapsed[1]) : null,
        blockingFindings: blocked });
    }
    await save('cli.json', cli);
    console.log(JSON.stringify(cli));
    // Exit 2 means the verification gate blocked the output; recorded for every size first.
    for (const run of cli) expect.soft(run.exitCode, `CLI exit code for ${run.rows} employees`).toBe(0);
  });
});

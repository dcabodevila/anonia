const fs = require('node:fs/promises');
const path = require('node:path');
const { spawn } = require('node:child_process');

const e2e = path.resolve(__dirname, '..');
const root = path.resolve(e2e, '..');
const cli = path.join(e2e, 'node_modules', '@playwright', 'test', 'cli.js');
const reportPath = path.join(e2e, 'test-results', 'playwright.json');
const ocrPath = path.join(e2e, '.runtime', 'ocr-status.json');
const environmentPath = path.join(e2e, '.runtime', 'environment.json');

function run() {
  return new Promise(resolve => {
    const child = spawn(process.execPath, [cli, 'test'], {
      cwd: e2e, stdio: 'inherit', windowsHide: true
    });
    child.on('close', (code, signal) => resolve({ code: code ?? 1, signal }));
    child.on('error', error => resolve({ code: 1, error: error.message }));
  });
}

async function readJson(file, fallback) {
  try { return JSON.parse(await fs.readFile(file, 'utf8')); } catch (_) { return fallback; }
}

function javaVersion() {
  return new Promise(resolve => {
    const child = spawn('java', ['-version'], { windowsHide: true });
    let output = '';
    child.stderr.on('data', chunk => { output += chunk; });
    child.once('error', () => resolve(null));
    child.once('close', () => resolve(output.trim().split(/\r?\n/)[0] || null));
  });
}

async function main() {
  const result = await run();
  const report = await readJson(reportPath, null);
  const ocr = await readJson(ocrPath, { status: 'not-executed' });
  const browserEnvironment = await readJson(environmentPath, {});
  const summary = {
    runner: 'Playwright',
    generatedAt: new Date().toISOString(),
    command: 'npm --prefix e2e test',
    exitCode: result.code,
    signal: result.signal || null,
    root,
    environment: {
      node: browserEnvironment.node || process.version,
      java: await javaVersion(),
      browser: browserEnvironment.browser || null
    },
    ocr,
    totals: report?.stats || null,
    report: 'test-results/playwright.json',
    htmlReport: 'playwright-report/index.html',
    serverLog: '.runtime/server.log'
  };
  await fs.mkdir(path.dirname(reportPath), { recursive: true });
  await fs.writeFile(path.join(e2e, 'test-results', 'summary.json'), JSON.stringify(summary, null, 2));
  process.exitCode = result.code;
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

const { defineConfig } = require('@playwright/test');

// Opt-in performance suite for large HR-style workbooks (see docs/e2e/hr-excel-performance.md).
// Kept out of the default `npm test` run because it is slow by design.
module.exports = defineConfig({
  testDir: './tests-perf',
  globalSetup: './scripts/perf-setup.js',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  timeout: 600_000,
  expect: { timeout: 300_000 },
  use: {
    headless: true,
    browserName: 'chromium',
    viewport: { width: 1600, height: 900 },
    permissions: ['clipboard-read', 'clipboard-write'],
    screenshot: 'only-on-failure',
    trace: 'off'
  },
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results-perf/playwright.json' }]
  ]
});

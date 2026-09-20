const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  globalSetup: './scripts/server.js',
  fullyParallel: false,
  workers: 1,
  forbidOnly: true,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  use: {
    headless: true,
    browserName: 'chromium',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure'
  },
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/playwright.json' }],
    ['html', { outputFolder: 'playwright-report', open: 'never' }]
  ]
});

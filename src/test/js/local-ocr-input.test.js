'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');

test('the document picker accepts PDF, JPEG, and PNG uploads', () => {
  const html = fs.readFileSync('src/main/resources/web/index.html', 'utf8');

  assert.match(html, /accept="application\/pdf,image\/jpeg,image\/png,.pdf,.jpg,.jpeg,.png"/);
});

const path = require('node:path');
const { spawn } = require('node:child_process');
const serverSetup = require('./server');

const root = path.resolve(__dirname, '..', '..');
const jar = path.join(root, 'target', 'doc-anonymizer.jar');
const output = path.join(root, 'e2e', '.runtime', 'hr-fixtures');

/** Sizes are employee counts; override with HR_SIZES="50,300". */
const sizes = (process.env.HR_SIZES || '50,150,300').split(',').map(s => s.trim());

function generate() {
  return new Promise((resolve, reject) => {
    const child = spawn('java', ['-Dfile.encoding=UTF-8', '-cp', jar,
      path.join(root, 'e2e', 'perf', 'HrWorkbookGenerator.java'), output, ...sizes], {
      cwd: root, windowsHide: true, stdio: 'inherit'
    });
    child.once('error', reject);
    child.once('exit', code => code === 0 ? resolve() : reject(
      new Error(`HR workbook generator exited with ${code}`)));
  });
}

// Reuses the default suite setup (fixtures, isolated port, owned Java server and teardown),
// then writes the HR workbooks with the same packaged JAR.
module.exports = async function perfSetup() {
  const teardown = await serverSetup();
  try {
    await generate();
  } catch (error) {
    await teardown();
    throw error;
  }
  return teardown;
};

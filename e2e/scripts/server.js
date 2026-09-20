const fs = require('node:fs/promises');
const net = require('node:net');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { createFixtures } = require('./fixtures');

const root = path.resolve(__dirname, '..', '..');
const runtime = path.join(root, 'e2e', '.runtime');

function reservePort() {
  return new Promise((resolve, reject) => {
    const socket = net.createServer();
    socket.once('error', reject);
    socket.listen(0, '127.0.0.1', () => {
      const { port } = socket.address();
      socket.close(error => error ? reject(error) : resolve(port));
    });
  });
}

async function waitForHealth(baseUrl, process) {
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (process.exitCode !== null) throw new Error(`Java server exited with ${process.exitCode}`);
    try {
      const response = await fetch(`${baseUrl}/health`);
      if (response.status === 200 && await response.text() === 'ok') return;
    } catch (_) {
      // The process is still binding its local port.
    }
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  throw new Error(`Java server did not become healthy at ${baseUrl}`);
}

async function stop(process) {
  if (process.exitCode !== null) return;
  process.kill('SIGTERM');
  await Promise.race([
    new Promise(resolve => process.once('exit', resolve)),
    new Promise(resolve => setTimeout(resolve, 5_000))
  ]);
  if (process.exitCode === null) process.kill('SIGKILL');
}

function generateSeededPdf(jar, output) {
  return new Promise((resolve, reject) => {
    const generator = spawn('java', ['-cp', jar,
      'com.docanonymizer.tools.SampleDocumentGenerator', output], {
      cwd: root, windowsHide: true, stdio: 'ignore'
    });
    generator.once('error', reject);
    generator.once('exit', code => code === 0 ? resolve() : reject(
      new Error(`Synthetic PDF generator exited with ${code}`)));
  });
}

module.exports = async function globalSetup() {
  await fs.mkdir(runtime, { recursive: true });
  await createFixtures(runtime);
  const jar = path.join(root, 'target', 'doc-anonymizer.jar');
  try {
    await fs.access(jar);
  } catch (_) {
    throw new Error('Missing target/doc-anonymizer.jar. Run mvn -o -DskipTests package first.');
  }

  const seededPdf = path.join(runtime, 'fixtures', 'seeded.pdf');
  await generateSeededPdf(jar, seededPdf);
  await fs.copyFile(seededPdf, path.join(runtime, 'fixtures', 'replacement.pdf'));
  const port = await reservePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const serverLog = await fs.open(path.join(runtime, 'server.log'), 'w');
  const server = spawn('java', ['-cp', jar, 'com.docanonymizer.adapter.web.WebServer', String(port)], {
    cwd: root,
    windowsHide: true,
    stdio: ['ignore', serverLog.fd, serverLog.fd]
  });
  try {
    await waitForHealth(baseUrl, server);
  } catch (error) {
    await stop(server);
    await serverLog.close();
    throw error;
  }
  await fs.writeFile(path.join(runtime, 'server.json'), JSON.stringify({ baseUrl, port }, null, 2));

  return async () => {
    await stop(server);
    await serverLog.close();
  };
};

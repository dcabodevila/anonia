const fs = require('node:fs/promises');
const path = require('node:path');
const { spawn } = require('node:child_process');
const zlib = require('node:zlib');

const glyphs = {
  A: ['01110', '10001', '10001', '11111', '10001', '10001', '10001'],
  D: ['11110', '10001', '10001', '10001', '10001', '10001', '11110'],
  E: ['11111', '10000', '11110', '10000', '10000', '10000', '11111'],
  I: ['11111', '00100', '00100', '00100', '00100', '00100', '11111'],
  J: ['00111', '00010', '00010', '00010', '10010', '10010', '01100'],
  L: ['10000', '10000', '10000', '10000', '10000', '10000', '11111'],
  N: ['10001', '11001', '10101', '10011', '10001', '10001', '10001'],
  O: ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
  P: ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
  R: ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
  U: ['10001', '10001', '10001', '10001', '10001', '10001', '01110'],
  Z: ['11111', '00001', '00010', '00100', '01000', '10000', '11111'],
  1: ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
  2: ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
  3: ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
  4: ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
  5: ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
  6: ['00110', '01000', '10000', '11110', '10001', '10001', '01110'],
  7: ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
  8: ['01110', '10001', '10001', '01110', '10001', '10001', '01110']
};

function crc32(buffer) {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const name = Buffer.from(type);
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(Buffer.concat([name, data])));
  return Buffer.concat([length, name, data, checksum]);
}

function textPng(text) {
  const scale = 5;
  const margin = 12;
  const width = margin * 2 + text.length * 6 * scale;
  const height = margin * 2 + 7 * scale;
  const pixels = Buffer.alloc((width * 3 + 1) * height, 255);
  for (let y = 0; y < height; y++) pixels[y * (width * 3 + 1)] = 0;
  for (let charIndex = 0; charIndex < text.length; charIndex++) {
    const glyph = glyphs[text[charIndex]];
    if (!glyph) continue;
    for (let row = 0; row < glyph.length; row++) for (let column = 0; column < glyph[row].length; column++) {
      if (glyph[row][column] !== '1') continue;
      for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
        const x = margin + (charIndex * 6 + column) * scale + dx;
        const y = margin + row * scale + dy;
        const offset = y * (width * 3 + 1) + 1 + x * 3;
        pixels[offset] = pixels[offset + 1] = pixels[offset + 2] = 0;
      }
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 2;
  return Buffer.concat([
    Buffer.from('\x89PNG\r\n\x1a\n', 'binary'),
    chunk('IHDR', header), chunk('IDAT', zlib.deflateSync(pixels)), chunk('IEND', Buffer.alloc(0))
  ]);
}

function runJava(args) {
  return new Promise((resolve, reject) => {
    const child = spawn('java', args, { windowsHide: true, stdio: 'ignore' });
    child.once('error', reject);
    child.once('exit', code => code === 0 ? resolve() : reject(
      new Error(`JPEG fixture generator exited with ${code}`)));
  });
}

async function createJpegFixture(runtime, fixtureDir) {
  const generator = path.join(runtime, 'JpegFixture.java');
  await fs.writeFile(generator, `
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
class JpegFixture {
  public static void main(String[] args) throws Exception {
    BufferedImage image = new BufferedImage(2200, 360, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 44));
    graphics.drawString("JUAN PEREZ LOPEZ PRESENTA EL DNI 12345678Z", 60, 130);
    graphics.drawString("JUAN PEREZ LOPEZ CONFIRMA EL DOCUMENTO", 60, 240);
    graphics.dispose();
    if (!ImageIO.write(image, "jpg", new File(args[0]))) throw new IllegalStateException("No JPEG writer");
  }
}`);
  await runJava(['-Djava.awt.headless=true', generator, path.join(fixtureDir, 'ocr-seeded.jpg')]);
}

async function createFixtures(runtime) {
  const fixtureDir = path.join(runtime, 'fixtures');
  await fs.mkdir(fixtureDir, { recursive: true });
  await fs.writeFile(path.join(fixtureDir, 'invalid.txt'), 'not a supported document');
  await fs.writeFile(path.join(fixtureDir, 'empty.pdf'), Buffer.alloc(0));
  await fs.writeFile(path.join(fixtureDir, 'corrupt.pdf'), '%PDF-1.7\nnot a real PDF');
  await fs.writeFile(path.join(fixtureDir, 'ocr-seeded.png'),
    textPng('JUAN PEREZ LOPEZ JUAN PEREZ LOPEZ JUAN PEREZ LOPEZ DNI 12345678Z'));
  await createJpegFixture(runtime, fixtureDir);
  return fixtureDir;
}

module.exports = { createFixtures };

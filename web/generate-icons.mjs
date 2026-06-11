// One-off generator for the PWA icons. Pure Node (zlib only) — no dependencies.
// Produces a clean QR-style glyph on a dark tile. Run: `node generate-icons.mjs`.
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(__dirname, 'public', 'icons');
fs.mkdirSync(OUT, { recursive: true });

const BG = [11, 15, 20]; // #0b0f14
const TILE = [17, 24, 34]; // #111822
const FG = [79, 156, 249]; // #4f9cf9 accent

function crc32(buf) {
  let c = ~0;
  for (let i = 0; i < buf.length; i++) {
    c ^= buf[i];
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
  }
  return ~c >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const body = Buffer.concat([typeBuf, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function encodePng(size, pixels) {
  // pixels: Uint8Array of RGB, size*size*3
  const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // color type RGB
  // 10,11,12 = compression, filter, interlace = 0
  const raw = Buffer.alloc(size * (size * 3 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 3 + 1)] = 0; // filter: none
    pixels.copy
      ? pixels.copy(raw, y * (size * 3 + 1) + 1, y * size * 3, (y + 1) * size * 3)
      : raw.set(pixels.subarray(y * size * 3, (y + 1) * size * 3), y * (size * 3 + 1) + 1);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([
    sig,
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// A fixed 9x9 QR-like glyph (1 = accent module). Three finder patterns + accents.
const GLYPH = [
  '111111010',
  '100001011',
  '101101001',
  '101101110',
  '100001010',
  '111111001',
  '000000111',
  '011010101',
  '010011111',
];

function makeIcon(size) {
  const px = Buffer.alloc(size * size * 3);
  const set = (x, y, c) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    const i = (y * size + x) * 3;
    px[i] = c[0];
    px[i + 1] = c[1];
    px[i + 2] = c[2];
  };
  // Background
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) set(x, y, BG);

  // Rounded tile
  const pad = Math.round(size * 0.08);
  const tileSize = size - pad * 2;
  const radius = Math.round(tileSize * 0.22);
  const inCorner = (x, y) => {
    const cx = x < pad + radius ? pad + radius : x > size - pad - radius ? size - pad - radius : x;
    const cy = y < pad + radius ? pad + radius : y > size - pad - radius ? size - pad - radius : y;
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2;
  };
  for (let y = pad; y < size - pad; y++) {
    for (let x = pad; x < size - pad; x++) {
      if (inCorner(x, y)) set(x, y, TILE);
    }
  }

  // QR glyph centered inside the tile
  const grid = GLYPH.length;
  const gpad = Math.round(tileSize * 0.16);
  const area = tileSize - gpad * 2;
  const cell = Math.floor(area / grid);
  const originX = pad + gpad + Math.floor((area - cell * grid) / 2);
  const originY = pad + gpad + Math.floor((area - cell * grid) / 2);
  for (let gy = 0; gy < grid; gy++) {
    for (let gx = 0; gx < grid; gx++) {
      if (GLYPH[gy][gx] === '1') {
        for (let dy = 0; dy < cell; dy++) {
          for (let dx = 0; dx < cell; dx++) {
            set(originX + gx * cell + dx, originY + gy * cell + dy, FG);
          }
        }
      }
    }
  }
  return encodePng(size, px);
}

for (const size of [192, 512, 180]) {
  const name = size === 180 ? 'apple-touch-icon.png' : `icon-${size}.png`;
  fs.writeFileSync(path.join(OUT, name), makeIcon(size));
  console.log('wrote', name);
}

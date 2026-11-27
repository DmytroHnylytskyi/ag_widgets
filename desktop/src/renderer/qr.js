'use strict';

/**
 * QR Code encoder — ISO/IEC 18004, versions 1-10, byte mode, EC level M (15%).
 *
 * Pure JavaScript, no dependencies and no DOM access: the matrix maths runs in
 * Node (tests) and the browser (`window.AGW.qr` for settings.js). Text is
 * encoded as UTF-8 bytes, so Cyrillic pairing URIs work.
 *
 * Public API:
 *   encodeQrMatrix(text) -> { version, size, mask, ecLevel: 'M', modules, text }
 *   renderQrSvg(text, { quietZone = 4, moduleSize = 1 }) -> string
 *
 * `encodeQrMatrix` throws an `Error` with `error.code === 'capacity'` when the
 * text exceeds version 10-M (213 bytes) and `error.code === 'empty'` for an
 * empty string.
 */

(function () {
  const MAX_VERSION = 10;
  const EC_LEVEL = 'M';

  /** Total codewords (data + error correction) per version, V1..V10. */
  const TOTAL_CODEWORDS = [26, 44, 70, 100, 134, 172, 196, 242, 292, 346];
  /** EC codewords per block at level M, V1..V10. */
  const EC_CODEWORDS_PER_BLOCK = [10, 16, 26, 18, 24, 16, 18, 22, 22, 26];
  /** Number of EC blocks at level M, V1..V10. */
  const NUM_BLOCKS = [1, 1, 1, 2, 2, 4, 4, 4, 5, 5];
  /** Alignment pattern centre coordinates, V1..V10. */
  const ALIGNMENT_POSITIONS = [
    [],
    [6, 18],
    [6, 22],
    [6, 26],
    [6, 30],
    [6, 34],
    [6, 22, 38],
    [6, 24, 42],
    [6, 26, 46],
    [6, 28, 50],
  ];

  /** Pad codewords appended after the terminator (ISO §8.4.9). */
  const PAD_BYTES = [0xec, 0x11];
  const MODE_BYTE = 0b0100;

  // --- GF(256) arithmetic --------------------------------------------------
  // Primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11d).

  const GF_EXP = new Uint8Array(512);
  const GF_LOG = new Uint8Array(256);
  (function initGaloisField() {
    let value = 1;
    for (let i = 0; i < 255; i += 1) {
      GF_EXP[i] = value;
      GF_LOG[value] = i;
      value <<= 1;
      if (value & 0x100) value ^= 0x11d;
    }
    for (let i = 255; i < 512; i += 1) GF_EXP[i] = GF_EXP[i - 255];
  })();

  function gfMultiply(a, b) {
    if (a === 0 || b === 0) return 0;
    return GF_EXP[GF_LOG[a] + GF_LOG[b]];
  }

  /** Monic generator polynomial of the given degree, leading term omitted. */
  function rsGeneratorPolynomial(degree) {
    const result = [];
    for (let i = 0; i < degree - 1; i += 1) result.push(0);
    result.push(1);
    let root = 1;
    for (let i = 0; i < degree; i += 1) {
      for (let j = 0; j < result.length; j += 1) {
        result[j] = gfMultiply(result[j], root);
        if (j + 1 < result.length) result[j] ^= result[j + 1];
      }
      root = gfMultiply(root, 2);
    }
    return result;
  }

  /** Reed-Solomon remainder (the EC codewords) for one data block. */
  function rsRemainder(data, generator) {
    const result = new Array(generator.length).fill(0);
    for (let i = 0; i < data.length; i += 1) {
      const factor = data[i] ^ result[0];
      result.shift();
      result.push(0);
      for (let j = 0; j < result.length; j += 1) {
        result[j] ^= gfMultiply(generator[j], factor);
      }
    }
    return result;
  }

  // --- helpers -------------------------------------------------------------

  function qrError(code, message) {
    const error = new Error(message);
    error.code = code;
    return error;
  }

  function getBit(value, index) {
    return (value >>> index) & 1;
  }

  function pushBits(target, value, length) {
    for (let i = length - 1; i >= 0; i -= 1) target.push(getBit(value, i));
  }

  function createMatrix(size) {
    const matrix = new Array(size);
    for (let i = 0; i < size; i += 1) matrix[i] = new Array(size).fill(false);
    return matrix;
  }

  function cloneMatrix(matrix) {
    return matrix.map(function (row) {
      return row.slice();
    });
  }

  /** UTF-8 bytes without `charCodeAt` so surrogate pairs encode correctly. */
  function utf8Bytes(text) {
    if (typeof TextEncoder === 'function') {
      return Array.prototype.slice.call(new TextEncoder().encode(text));
    }
    const bytes = [];
    for (let i = 0; i < text.length; i += 1) {
      const code = text.codePointAt(i);
      if (code > 0xffff) i += 1;
      if (code <= 0x7f) {
        bytes.push(code);
      } else if (code <= 0x7ff) {
        bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
      } else if (code <= 0xffff) {
        bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
      } else {
        bytes.push(
          0xf0 | (code >> 18),
          0x80 | ((code >> 12) & 0x3f),
          0x80 | ((code >> 6) & 0x3f),
          0x80 | (code & 0x3f),
        );
      }
    }
    return bytes;
  }

  function characterCountBits(version) {
    return version <= 9 ? 8 : 16;
  }

  function dataCodewordCount(version) {
    const index = version - 1;
    return TOTAL_CODEWORDS[index] - EC_CODEWORDS_PER_BLOCK[index] * NUM_BLOCKS[index];
  }

  // --- data encoding -------------------------------------------------------

  function chooseVersion(byteLength) {
    for (let version = 1; version <= MAX_VERSION; version += 1) {
      const capacityBits = dataCodewordCount(version) * 8;
      const neededBits = 4 + characterCountBits(version) + byteLength * 8;
      if (neededBits <= capacityBits) return version;
    }
    throw qrError(
      'capacity',
      'Текст слишком длинный для QR-кода: максимум 213 байт (версия 10, уровень M).',
    );
  }

  function buildDataCodewords(bytes, version) {
    const capacityBits = dataCodewordCount(version) * 8;
    const bits = [];
    pushBits(bits, MODE_BYTE, 4);
    pushBits(bits, bytes.length, characterCountBits(version));
    for (let i = 0; i < bytes.length; i += 1) pushBits(bits, bytes[i], 8);

    if (bits.length > capacityBits) {
      throw qrError('capacity', 'Текст не помещается в выбранную версию QR-кода.');
    }

    // Terminator: up to four zero bits, then pad to a whole codeword.
    const terminator = Math.min(4, capacityBits - bits.length);
    pushBits(bits, 0, terminator);
    while (bits.length % 8 !== 0) bits.push(0);

    const codewords = [];
    for (let i = 0; i < bits.length; i += 8) {
      let value = 0;
      for (let j = 0; j < 8; j += 1) value = (value << 1) | bits[i + j];
      codewords.push(value);
    }
    let padIndex = 0;
    while (codewords.length < capacityBits / 8) {
      codewords.push(PAD_BYTES[padIndex % 2]);
      padIndex += 1;
    }
    return codewords;
  }

  function splitIntoBlocks(dataCodewords, version, ecPerBlock, numBlocks) {
    const rawCodewords = TOTAL_CODEWORDS[version - 1];
    const numShortBlocks = numBlocks - (rawCodewords % numBlocks);
    const shortBlockLength = Math.floor(rawCodewords / numBlocks);
    const generator = rsGeneratorPolynomial(ecPerBlock);
    const blocks = [];
    let offset = 0;
    for (let i = 0; i < numBlocks; i += 1) {
      const dataLength = shortBlockLength - ecPerBlock + (i < numShortBlocks ? 0 : 1);
      const data = dataCodewords.slice(offset, offset + dataLength);
      offset += dataLength;
      blocks.push({ data: data, ec: rsRemainder(data, generator) });
    }
    return blocks;
  }

  function interleaveBlocks(blocks, ecPerBlock) {
    const result = [];
    let maxDataLength = 0;
    for (let i = 0; i < blocks.length; i += 1) {
      maxDataLength = Math.max(maxDataLength, blocks[i].data.length);
    }
    for (let i = 0; i < maxDataLength; i += 1) {
      for (let b = 0; b < blocks.length; b += 1) {
        if (i < blocks[b].data.length) result.push(blocks[b].data[i]);
      }
    }
    for (let i = 0; i < ecPerBlock; i += 1) {
      for (let b = 0; b < blocks.length; b += 1) result.push(blocks[b].ec[i]);
    }
    return result;
  }

  // --- matrix construction -------------------------------------------------

  /** Visit every format-information module with its bit index (0..14). */
  function formatPositions(size, callback) {
    for (let i = 0; i < 6; i += 1) callback(8, i, i);
    callback(8, 7, 6);
    callback(8, 8, 7);
    callback(7, 8, 8);
    for (let i = 9; i < 15; i += 1) callback(14 - i, 8, i);
    for (let i = 0; i < 8; i += 1) callback(size - 1 - i, 8, i);
    for (let i = 8; i < 15; i += 1) callback(8, size - 15 + i, i);
  }

  /** 15-bit BCH(15,5) format information, masked with 0x5412. */
  function formatBitsForMask(mask) {
    const dataBits = ((0b00 << 3) | (mask & 7)); // EC level M == 0b00
    let remainder = dataBits;
    for (let i = 0; i < 10; i += 1) {
      remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
    }
    return ((dataBits << 10) | remainder) ^ 0x5412;
  }

  function placeFormatBits(modules, size, mask) {
    const bits = formatBitsForMask(mask);
    formatPositions(size, function (x, y, bitIndex) {
      modules[y][x] = getBit(bits, bitIndex) === 1;
    });
    modules[size - 8][8] = true; // dark module
  }

  function drawFinderPattern(setFunction, centerX, centerY) {
    for (let dy = -4; dy <= 4; dy += 1) {
      for (let dx = -4; dx <= 4; dx += 1) {
        const distance = Math.max(Math.abs(dx), Math.abs(dy));
        setFunction(centerX + dx, centerY + dy, distance !== 2 && distance !== 4);
      }
    }
  }

  function drawAlignmentPattern(setFunction, centerX, centerY) {
    for (let dy = -2; dy <= 2; dy += 1) {
      for (let dx = -2; dx <= 2; dx += 1) {
        const distance = Math.max(Math.abs(dx), Math.abs(dy));
        setFunction(centerX + dx, centerY + dy, distance !== 1);
      }
    }
  }

  function drawVersionInformation(setFunction, size, version) {
    let remainder = version;
    for (let i = 0; i < 12; i += 1) {
      remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1f25);
    }
    const bits = (version << 12) | remainder;
    for (let i = 0; i < 18; i += 1) {
      const dark = getBit(bits, i) === 1;
      const a = size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      setFunction(a, b, dark);
      setFunction(b, a, dark);
    }
  }

  /**
   * Build the module grid with every function pattern and the format-info
   * placeholder in place. Data modules are still light.
   */
  function buildFunctionPatterns(version) {
    const size = 17 + 4 * version;
    const modules = createMatrix(size);
    const isFunction = createMatrix(size);

    function setFunction(x, y, dark) {
      if (x < 0 || y < 0 || x >= size || y >= size) return;
      modules[y][x] = Boolean(dark);
      isFunction[y][x] = true;
    }

    // Timing patterns run the full width/height, then finders overwrite them.
    for (let i = 0; i < size; i += 1) {
      setFunction(6, i, i % 2 === 0);
      setFunction(i, 6, i % 2 === 0);
    }

    drawFinderPattern(setFunction, 3, 3);
    drawFinderPattern(setFunction, size - 4, 3);
    drawFinderPattern(setFunction, 3, size - 4);

    const positions = ALIGNMENT_POSITIONS[version - 1] || [];
    for (let a = 0; a < positions.length; a += 1) {
      for (let b = 0; b < positions.length; b += 1) {
        const x = positions[a];
        const y = positions[b];
        const overlapsFinder =
          (x === 6 && y === 6)
          || (x === 6 && y === size - 7)
          || (x === size - 7 && y === 6);
        if (!overlapsFinder) drawAlignmentPattern(setFunction, x, y);
      }
    }

    formatPositions(size, function (x, y) {
      isFunction[y][x] = true;
    });
    placeFormatBits(modules, size, 0);

    if (version >= 7) drawVersionInformation(setFunction, size, version);

    return { size: size, modules: modules, isFunction: isFunction };
  }

  /** Two-column zig-zag placement, skipping column 6 and function modules. */
  function placeDataCodewords(base, codewords) {
    const size = base.size;
    const modules = base.modules;
    const isFunction = base.isFunction;
    const totalBits = codewords.length * 8;
    let bitIndex = 0;

    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vertical = 0; vertical < size; vertical += 1) {
        for (let offset = 0; offset < 2; offset += 1) {
          const x = right - offset;
          const upward = ((right + 1) & 2) === 0;
          const y = upward ? size - 1 - vertical : vertical;
          if (!isFunction[y][x] && bitIndex < totalBits) {
            const codeword = codewords[bitIndex >> 3];
            modules[y][x] = getBit(codeword, 7 - (bitIndex & 7)) === 1;
            bitIndex += 1;
          }
        }
      }
    }
  }

  function maskCondition(mask, row, column) {
    switch (mask) {
      case 0:
        return (row + column) % 2 === 0;
      case 1:
        return row % 2 === 0;
      case 2:
        return column % 3 === 0;
      case 3:
        return (row + column) % 3 === 0;
      case 4:
        return (Math.floor(row / 2) + Math.floor(column / 3)) % 2 === 0;
      case 5:
        return ((row * column) % 2) + ((row * column) % 3) === 0;
      case 6:
        return (((row * column) % 2) + ((row * column) % 3)) % 2 === 0;
      case 7:
        return (((row + column) % 2) + ((row * column) % 3)) % 2 === 0;
      default:
        return false;
    }
  }

  function applyMask(base, mask) {
    const size = base.size;
    const modules = cloneMatrix(base.modules);
    for (let row = 0; row < size; row += 1) {
      for (let column = 0; column < size; column += 1) {
        if (!base.isFunction[row][column] && maskCondition(mask, row, column)) {
          modules[row][column] = !modules[row][column];
        }
      }
    }
    return modules;
  }

  // --- mask penalty --------------------------------------------------------

  const FINDER_LIKE_A = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0];
  const FINDER_LIKE_B = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1];

  function matchesPattern(values, start, pattern) {
    for (let i = 0; i < pattern.length; i += 1) {
      if ((values[start + i] ? 1 : 0) !== pattern[i]) return false;
    }
    return true;
  }

  function penaltyScore(modules, size) {
    let score = 0;

    // N1: runs of five or more same-coloured modules, rows then columns.
    for (let row = 0; row < size; row += 1) {
      let run = 1;
      for (let column = 1; column < size; column += 1) {
        if (modules[row][column] === modules[row][column - 1]) {
          run += 1;
        } else {
          if (run >= 5) score += 3 + (run - 5);
          run = 1;
        }
      }
      if (run >= 5) score += 3 + (run - 5);
    }
    for (let column = 0; column < size; column += 1) {
      let run = 1;
      for (let row = 1; row < size; row += 1) {
        if (modules[row][column] === modules[row - 1][column]) {
          run += 1;
        } else {
          if (run >= 5) score += 3 + (run - 5);
          run = 1;
        }
      }
      if (run >= 5) score += 3 + (run - 5);
    }

    // N2: 2x2 blocks of one colour.
    for (let row = 0; row < size - 1; row += 1) {
      for (let column = 0; column < size - 1; column += 1) {
        const value = modules[row][column];
        if (
          value === modules[row][column + 1]
          && value === modules[row + 1][column]
          && value === modules[row + 1][column + 1]
        ) {
          score += 3;
        }
      }
    }

    // N3: finder-like 1:1:3:1:1 sequences with four light modules beside them.
    for (let row = 0; row < size; row += 1) {
      for (let start = 0; start <= size - 11; start += 1) {
        if (
          matchesPattern(modules[row], start, FINDER_LIKE_A)
          || matchesPattern(modules[row], start, FINDER_LIKE_B)
        ) {
          score += 40;
        }
      }
    }
    const column = new Array(size);
    for (let c = 0; c < size; c += 1) {
      for (let r = 0; r < size; r += 1) column[r] = modules[r][c];
      for (let start = 0; start <= size - 11; start += 1) {
        if (
          matchesPattern(column, start, FINDER_LIKE_A)
          || matchesPattern(column, start, FINDER_LIKE_B)
        ) {
          score += 40;
        }
      }
    }

    // N4: deviation of the dark-module share from 50%, in 5% steps.
    let dark = 0;
    for (let row = 0; row < size; row += 1) {
      for (let c = 0; c < size; c += 1) {
        if (modules[row][c]) dark += 1;
      }
    }
    const total = size * size;
    const percent = (dark * 100) / total;
    const previous = Math.floor(percent / 5) * 5;
    const next = Math.ceil(percent / 5) * 5;
    const steps = Math.min(Math.abs(previous - 50), Math.abs(next - 50)) / 5;
    score += steps * 10;

    return score;
  }

  // --- public API ----------------------------------------------------------

  function encodeQrMatrix(text) {
    if (typeof text !== 'string' || text.length === 0) {
      throw qrError('empty', 'Пустой текст: QR-код не строится.');
    }

    const bytes = utf8Bytes(text);
    const version = chooseVersion(bytes.length);
    const dataCodewords = buildDataCodewords(bytes, version);
    const ecPerBlock = EC_CODEWORDS_PER_BLOCK[version - 1];
    const numBlocks = NUM_BLOCKS[version - 1];
    const blocks = splitIntoBlocks(dataCodewords, version, ecPerBlock, numBlocks);
    const codewords = interleaveBlocks(blocks, ecPerBlock);

    const base = buildFunctionPatterns(version);
    placeDataCodewords(base, codewords);

    let best = null;
    for (let mask = 0; mask < 8; mask += 1) {
      const modules = applyMask(base, mask);
      placeFormatBits(modules, base.size, mask);
      const score = penaltyScore(modules, base.size);
      if (!best || score < best.score) {
        best = { mask: mask, modules: modules, score: score };
      }
    }

    return {
      version: version,
      size: base.size,
      mask: best.mask,
      ecLevel: EC_LEVEL,
      modules: best.modules,
      text: text,
    };
  }

  function renderQrSvg(text, options) {
    const settings = options || {};
    const quietZone = Number.isInteger(settings.quietZone) && settings.quietZone >= 0
      ? settings.quietZone
      : 4;
    const moduleSize = Number.isFinite(settings.moduleSize) && settings.moduleSize > 0
      ? settings.moduleSize
      : 1;

    const qr = encodeQrMatrix(text);
    const dimension = qr.size + quietZone * 2;
    let path = '';
    for (let row = 0; row < qr.size; row += 1) {
      for (let column = 0; column < qr.size; column += 1) {
        if (qr.modules[row][column]) {
          path += 'M' + (column + quietZone) + ' ' + (row + quietZone) + 'h1v1h-1z';
        }
      }
    }
    const pixels = dimension * moduleSize;

    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + dimension + ' ' + dimension
      + '" width="' + pixels + '" height="' + pixels
      + '" role="img" aria-label="QR-код для сопряжения" shape-rendering="crispEdges">'
      + '<rect width="' + dimension + '" height="' + dimension + '" fill="#ffffff"/>'
      + '<path fill="#000000" d="' + path + '"/>'
      + '</svg>';
  }

  const api = {
    encodeQrMatrix: encodeQrMatrix,
    renderQrSvg: renderQrSvg,
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  }

  if (typeof window !== 'undefined') {
    const AGW = (window.AGW = window.AGW || {});
    AGW.qr = api;
  }
})();

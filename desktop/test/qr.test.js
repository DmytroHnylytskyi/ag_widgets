'use strict';

/**
 * Contract tests for the renderer QR encoder (`desktop/src/renderer/qr.js`).
 *
 * The module is a UMD bundle: under plain `node --test` it must resolve through
 * `module.exports` (there is no `window`), and it must not touch the DOM.
 * Everything exercised here is pure computation plus string rendering, so no
 * Electron, no network and no Python helper is involved.
 *
 * Format and version information are NOT trusted from the encoder: the tests
 * decode the 15-bit and 18-bit blocks straight out of the module matrix at the
 * ISO 18004 coordinates, then validate the BCH codes with a local polynomial
 * division (generators 0x537 and 0x1F25) implemented in this file.
 */

const test = require('node:test');
const assert = require('node:assert/strict');

const qr = require('../src/renderer/qr.js');

/* ------------------------------------------------------------------ */
/* ISO tables and independent BCH helpers                              */
/* ------------------------------------------------------------------ */

/** Alignment-pattern centre coordinates per version (ISO 18004 Annex E). */
const ALIGNMENT_CENTRES = {
  1: [],
  2: [6, 18],
  3: [6, 22],
  4: [6, 26],
  5: [6, 30],
  6: [6, 34],
  7: [6, 22, 38],
  8: [6, 24, 42],
  9: [6, 26, 46],
  10: [6, 28, 50],
};

/** Format-information XOR mask mandated by ISO 18004 (EC level + mask). */
const FORMAT_XOR_MASK = 0x5412;
/** Generator polynomial of the BCH(15,5) format code. */
const FORMAT_BCH_POLY = 0x537;
/** Generator polynomial of the BCH(18,6) version code. */
const VERSION_BCH_POLY = 0x1f25;
/** EC level M is encoded as 0b00 in the two format-information bits. */
const EC_M_BITS = 0b00;

/** Number of significant bits in a non-negative integer. */
function bitLength(value) {
  let length = 0;
  let rest = value;
  while (rest > 0) {
    length += 1;
    rest >>>= 1;
  }
  return length;
}

/**
 * Remainder of a GF(2) polynomial division: divides `value` by `poly` and
 * returns what is left. A valid BCH codeword divides evenly (remainder 0).
 */
function bchRemainder(value, poly) {
  let remainder = value;
  const polyDegree = bitLength(poly) - 1;
  while (remainder !== 0 && bitLength(remainder) - 1 >= polyDegree) {
    remainder ^= poly << (bitLength(remainder) - 1 - polyDegree);
  }
  return remainder;
}

/** Read the 15-bit format block that wraps the top-left finder. */
function readFormatTopLeft(modules) {
  let bits = 0;
  const take = (index, dark) => {
    if (dark) bits |= 1 << index;
  };
  for (let i = 0; i <= 5; i += 1) take(i, modules[i][8]);
  take(6, modules[7][8]);
  take(7, modules[8][8]);
  take(8, modules[8][7]);
  for (let i = 9; i < 15; i += 1) take(i, modules[8][14 - i]);
  return bits;
}

/** Read the 15-bit format block along the bottom-left / top-right sides. */
function readFormatSplit(modules, size) {
  let bits = 0;
  const take = (index, dark) => {
    if (dark) bits |= 1 << index;
  };
  for (let i = 0; i < 8; i += 1) take(i, modules[8][size - 1 - i]);
  for (let i = 8; i < 15; i += 1) take(i, modules[size - 15 + i][8]);
  return bits;
}

/** Read the 18-bit version block in the top-right corner. */
function readVersionTopRight(modules, size) {
  let bits = 0;
  for (let i = 0; i < 18; i += 1) {
    const col = size - 11 + (i % 3);
    const row = Math.floor(i / 3);
    if (modules[row][col]) bits |= 1 << i;
  }
  return bits;
}

/** Read the 18-bit version block in the bottom-left corner. */
function readVersionBottomLeft(modules, size) {
  let bits = 0;
  for (let i = 0; i < 18; i += 1) {
    const row = size - 11 + (i % 3);
    const col = Math.floor(i / 3);
    if (modules[row][col]) bits |= 1 << i;
  }
  return bits;
}

/**
 * Alignment-pattern centres that are actually drawn: every combination of the
 * table positions except the three that would collide with a finder pattern.
 */
function placedAlignmentCentres(version, size) {
  const positions = ALIGNMENT_CENTRES[version];
  const centres = [];
  for (const row of positions) {
    for (const col of positions) {
      const overlapsTopLeft = row === 6 && col === 6;
      const overlapsTopRight = row === 6 && col === size - 7;
      const overlapsBottomLeft = row === size - 7 && col === 6;
      if (overlapsTopLeft || overlapsTopRight || overlapsBottomLeft) continue;
      centres.push([row, col]);
    }
  }
  return centres;
}

/** Whether (row, col) falls inside any placed 5x5 alignment pattern. */
function insideAlignment(row, col, centres) {
  return centres.some(
    ([centreRow, centreCol]) =>
      Math.abs(row - centreRow) <= 2 && Math.abs(col - centreCol) <= 2,
  );
}

/** Expected state of a 5x5 alignment pattern at its centre. */
function expectedAlignmentModule(deltaRow, deltaCol) {
  const ring = Math.max(Math.abs(deltaRow), Math.abs(deltaCol));
  if (ring === 2) return true; // dark border
  if (ring === 1) return false; // light ring
  return true; // dark centre
}

/** Expected state of a 7x7 finder pattern. */
function expectedFinderModule(deltaRow, deltaCol) {
  const border = deltaRow === 0 || deltaRow === 6 || deltaCol === 0 || deltaCol === 6;
  const centre = deltaRow >= 2 && deltaRow <= 4 && deltaCol >= 2 && deltaCol <= 4;
  return border || centre;
}

/** Assert the shared invariants of an `encodeQrMatrix` result. */
function assertMatrixShape(result, label) {
  assert.equal(result.ecLevel, 'M', `${label}: EC level must be M`);
  assert.ok(Number.isInteger(result.version), `${label}: version must be an integer`);
  assert.ok(
    result.version >= 1 && result.version <= 10,
    `${label}: version ${result.version} must be inside 1..10`,
  );
  assert.equal(
    result.size,
    21 + 4 * (result.version - 1),
    `${label}: size must follow 21 + 4 * (version - 1)`,
  );
  assert.equal(result.modules.length, result.size, `${label}: row count must equal size`);
  for (let row = 0; row < result.size; row += 1) {
    const line = result.modules[row];
    assert.ok(Array.isArray(line), `${label}: row ${row} must be an array`);
    assert.equal(line.length, result.size, `${label}: row ${row} must be ${result.size} wide`);
    for (let col = 0; col < result.size; col += 1) {
      assert.equal(
        typeof line[col],
        'boolean',
        `${label}: module (${row},${col}) must be a boolean`,
      );
    }
  }
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

test('the module exposes encodeQrMatrix and renderQrSvg as functions', () => {
  assert.equal(typeof qr.encodeQrMatrix, 'function', 'encodeQrMatrix must be exported');
  assert.equal(typeof qr.renderQrSvg, 'function', 'renderQrSvg must be exported');
});

test('encodeQrMatrix works in plain Node without a window object', () => {
  assert.equal(typeof globalThis.window, 'undefined', 'the test must run without a window');
  const result = qr.encodeQrMatrix('AGW');
  assert.equal(result.text, 'AGW', 'the source text must be echoed back');
});

/* ------------------------------------------------------------------ */
/* Versions and sizes                                                  */
/* ------------------------------------------------------------------ */

const QR_TEXTS = [
  'AGW',
  'agw://pair?v=1&t=agw-abc123',
  'a'.repeat(100),
  'a'.repeat(150),
  'a'.repeat(213),
];

test('versions and sizes stay consistent for growing payload lengths', () => {
  for (const text of QR_TEXTS) {
    const label = `text length ${text.length}`;
    const result = qr.encodeQrMatrix(text);
    assertMatrixShape(result, label);
    assert.equal(result.text, text, `${label}: text must be preserved`);
    assert.ok(Number.isInteger(result.mask), `${label}: mask must be an integer`);
  }
});

/* ------------------------------------------------------------------ */
/* Determinism                                                         */
/* ------------------------------------------------------------------ */

test('the same text always yields the same version, mask and matrix', () => {
  for (const text of QR_TEXTS) {
    const first = qr.encodeQrMatrix(text);
    const second = qr.encodeQrMatrix(text);
    const label = `text length ${text.length}`;
    assert.equal(first.version, second.version, `${label}: version must be stable`);
    assert.equal(first.mask, second.mask, `${label}: mask must be stable`);
    assert.equal(
      JSON.stringify(first.modules),
      JSON.stringify(second.modules),
      `${label}: module matrix must be byte-identical`,
    );
  }
});

/* ------------------------------------------------------------------ */
/* Function patterns                                                   */
/* ------------------------------------------------------------------ */

test('the three finder patterns carry the exact 7x7 drawing', () => {
  const corners = (size) => [
    [0, 0],
    [0, size - 7],
    [size - 7, 0],
  ];
  for (const text of QR_TEXTS) {
    const result = qr.encodeQrMatrix(text);
    const label = `version ${result.version}`;
    for (const [top, left] of corners(result.size)) {
      for (let deltaRow = 0; deltaRow < 7; deltaRow += 1) {
        for (let deltaCol = 0; deltaCol < 7; deltaCol += 1) {
          assert.equal(
            result.modules[top + deltaRow][left + deltaCol],
            expectedFinderModule(deltaRow, deltaCol),
            `${label}: finder at (${top},${left}) module (${deltaRow},${deltaCol})`,
          );
        }
      }
    }
  }
});

test('a light separator line surrounds every finder pattern', () => {
  for (const text of QR_TEXTS) {
    const result = qr.encodeQrMatrix(text);
    const { size, modules } = result;
    const label = `version ${result.version}`;

    // Top-left: row 7 and column 7.
    for (let i = 0; i <= 7; i += 1) {
      assert.equal(modules[7][i], false, `${label}: top-left separator row at col ${i}`);
      assert.equal(modules[i][7], false, `${label}: top-left separator column at row ${i}`);
    }

    // Top-right: row 7 and column size-8.
    for (let i = size - 8; i < size; i += 1) {
      assert.equal(modules[7][i], false, `${label}: top-right separator row at col ${i}`);
    }
    for (let i = 0; i <= 7; i += 1) {
      assert.equal(modules[i][size - 8], false, `${label}: top-right separator column at row ${i}`);
    }

    // Bottom-left: row size-8 and column 7.
    for (let i = 0; i <= 7; i += 1) {
      assert.equal(modules[size - 8][i], false, `${label}: bottom-left separator row at col ${i}`);
    }
    for (let i = size - 8; i < size; i += 1) {
      assert.equal(modules[i][7], false, `${label}: bottom-left separator column at row ${i}`);
    }
  }
});

test('the timing patterns alternate from dark between the finders', () => {
  for (const text of QR_TEXTS) {
    const result = qr.encodeQrMatrix(text);
    const { size, modules } = result;
    const centres = placedAlignmentCentres(result.version, size);
    const label = `version ${result.version}`;

    // Alignment patterns overwrite the timing lines where they coincide.
    for (let col = 8; col <= size - 9; col += 1) {
      if (insideAlignment(6, col, centres)) continue;
      assert.equal(
        modules[6][col],
        col % 2 === 0,
        `${label}: horizontal timing module at col ${col}`,
      );
    }
    for (let row = 8; row <= size - 9; row += 1) {
      if (insideAlignment(row, 6, centres)) continue;
      assert.equal(
        modules[row][6],
        row % 2 === 0,
        `${label}: vertical timing module at row ${row}`,
      );
    }
  }
});

test('the dark module sits at (size-8, 8)', () => {
  for (const text of QR_TEXTS) {
    const result = qr.encodeQrMatrix(text);
    assert.equal(
      result.modules[result.size - 8][8],
      true,
      `version ${result.version}: the dark module must be set`,
    );
  }
});

/* ------------------------------------------------------------------ */
/* Format information (independent BCH(15,5) check)                    */
/* ------------------------------------------------------------------ */

test('both format-information copies decode to the expected BCH codeword', () => {
  for (const text of QR_TEXTS) {
    const result = qr.encodeQrMatrix(text);
    const { modules, size, mask, version } = result;
    const label = `version ${version}, mask ${mask}`;

    assert.ok(mask >= 0 && mask <= 7, `${label}: mask must be in 0..7`);

    // Independent expectation: data bits (EC M, mask) plus its BCH remainder.
    const dataBits = (EC_M_BITS << 3) | mask;
    const remainder = bchRemainder(dataBits << 10, FORMAT_BCH_POLY);
    const expected = ((dataBits << 10) | remainder) ^ FORMAT_XOR_MASK;

    const topLeft = readFormatTopLeft(modules);
    const split = readFormatSplit(modules, size);

    assert.equal(topLeft, expected, `${label}: top-left format copy must match`);
    assert.equal(split, expected, `${label}: split format copy must match`);
    assert.equal(topLeft, split, `${label}: both format copies must be identical`);

    // The unmasked codeword must divide cleanly and carry the same 5 data bits.
    const unmasked = topLeft ^ FORMAT_XOR_MASK;
    assert.equal(
      bchRemainder(unmasked, FORMAT_BCH_POLY),
      0,
      `${label}: format BCH remainder must be zero`,
    );
    assert.equal(
      unmasked >> 10,
      dataBits,
      `${label}: format data bits must encode EC M and the mask`,
    );
    assert.equal(
      (unmasked >> 10) >> 3,
      EC_M_BITS,
      `${label}: format EC bits must be M`,
    );
  }
});

/* ------------------------------------------------------------------ */
/* Version information (independent BCH(18,6) check)                   */
/* ------------------------------------------------------------------ */

test('both version-information copies decode to the version with a zero remainder', () => {
  // 107 and 153 bytes are the first lengths that need V7 and V9, so the two
  // BCH(18,6) copies are checked for every version that carries them.
  const payloads = [...QR_TEXTS, 'a'.repeat(107), 'a'.repeat(153)];
  const verified = new Set();
  for (const text of payloads) {
    const result = qr.encodeQrMatrix(text);
    if (result.version < 7) continue;
    verified.add(result.version);
    const { modules, size, version } = result;
    const label = `version ${version}`;

    const expected = (version << 12) | bchRemainder(version << 12, VERSION_BCH_POLY);
    const topRight = readVersionTopRight(modules, size);
    const bottomLeft = readVersionBottomLeft(modules, size);

    assert.equal(topRight, expected, `${label}: top-right version copy must match`);
    assert.equal(bottomLeft, expected, `${label}: bottom-left version copy must match`);
    assert.equal(topRight, bottomLeft, `${label}: both version copies must be identical`);
    assert.equal(
      bchRemainder(topRight, VERSION_BCH_POLY),
      0,
      `${label}: version BCH remainder must be zero`,
    );
    assert.equal(topRight >> 12, version, `${label}: version field must equal the version`);
  }
  assert.ok(verified.has(7), 'version 7 must be exercised');
  assert.ok(verified.size >= 4, 'several versions must carry version information');
});

/* ------------------------------------------------------------------ */
/* Alignment patterns                                                  */
/* ------------------------------------------------------------------ */

test('every placed alignment pattern is an exact 5x5 bullseye', () => {
  // Lengths chosen so a minimal-version encoder walks V2..V10.
  const lengths = [15, 27, 43, 63, 85, 107, 123, 153, 181, 213];
  const seen = new Set();
  for (const length of lengths) {
    const result = qr.encodeQrMatrix('a'.repeat(length));
    if (result.version < 2) continue;
    if (seen.has(result.version)) continue;
    seen.add(result.version);

    const { size, modules, version } = result;
    const centres = placedAlignmentCentres(version, size);
    assert.ok(centres.length > 0, `version ${version}: at least one alignment pattern`);
    for (const [centreRow, centreCol] of centres) {
      for (let deltaRow = -2; deltaRow <= 2; deltaRow += 1) {
        for (let deltaCol = -2; deltaCol <= 2; deltaCol += 1) {
          assert.equal(
            modules[centreRow + deltaRow][centreCol + deltaCol],
            expectedAlignmentModule(deltaRow, deltaCol),
            `version ${version}: alignment centre (${centreRow},${centreCol}) ` +
              `module (${deltaRow},${deltaCol})`,
          );
        }
      }
    }
  }
  assert.ok(seen.size >= 3, 'the sweep must exercise several versions with alignment patterns');
});

/* ------------------------------------------------------------------ */
/* SVG rendering: quiet zone                                           */
/* ------------------------------------------------------------------ */

test('renderQrSvg sizes the viewBox from the quiet zone', () => {
  const text = 'AGW';
  const size = qr.encodeQrMatrix(text).size;

  const byDefault = qr.renderQrSvg(text);
  assert.ok(
    byDefault.includes(`viewBox="0 0 ${size + 8} ${size + 8}"`),
    'the default quiet zone of 4 must add 8 modules',
  );

  const none = qr.renderQrSvg(text, { quietZone: 0 });
  assert.ok(
    none.includes(`viewBox="0 0 ${size} ${size}"`),
    'a quiet zone of 0 must keep the raw size',
  );

  const two = qr.renderQrSvg(text, { quietZone: 2 });
  assert.ok(
    two.includes(`viewBox="0 0 ${size + 4} ${size + 4}"`),
    'a quiet zone of 2 must add 4 modules',
  );
});

/* ------------------------------------------------------------------ */
/* SVG structure                                                       */
/* ------------------------------------------------------------------ */

test('renderQrSvg produces a self-contained black-on-white image', () => {
  const text = 'agw://pair?v=1&t=agw-abc123';
  const size = qr.encodeQrMatrix(text).size;
  const extent = size + 8;
  const svg = qr.renderQrSvg(text);

  assert.ok(svg.startsWith('<svg'), 'the markup must start with the svg root');
  assert.ok(
    svg.includes('xmlns="http://www.w3.org/2000/svg"'),
    'the svg namespace must be declared',
  );
  assert.match(svg, /role="img"/, 'the svg must expose role="img"');
  assert.match(svg, /aria-label="[^"]+"/, 'the svg must carry a non-empty aria-label');
  assert.match(svg, /shape-rendering="crispEdges"/, 'the svg must disable anti-aliasing');

  // A white background covering the full viewBox.
  const rectTag = svg.match(/<rect\b[^>]*>/);
  assert.ok(rectTag, 'the svg must contain a background rect');
  assert.match(rectTag[0], /fill="#ffffff"/i, 'the background rect must be white');
  assert.match(
    rectTag[0],
    new RegExp(`width="${extent}"`),
    'the background rect must span the full width',
  );
  assert.match(
    rectTag[0],
    new RegExp(`height="${extent}"`),
    'the background rect must span the full height',
  );

  // Exactly one path, painted black.
  assert.equal(
    (svg.match(/<path\b/g) || []).length,
    1,
    'the modules must be drawn by exactly one path',
  );
  const pathTag = svg.match(/<path\b[^>]*>/);
  assert.ok(pathTag, 'the single path must be present');
  assert.match(pathTag[0], /fill="#000000"/i, 'the module path must be black');

  // No external resources and no theme-dependent colours.
  assert.doesNotMatch(svg, /href=/i, 'an image must not reference external content');
  assert.doesNotMatch(svg, /url\(/i, 'an image must not reference external content');
  assert.doesNotMatch(svg, /<image\b/i, 'an image must not embed a raster');
  assert.doesNotMatch(svg, /<script\b/i, 'an image must not embed a script');
  assert.doesNotMatch(svg, /<style\b/i, 'an image must not embed a stylesheet');
  assert.doesNotMatch(svg, /currentColor/i, 'QR colours must not follow the theme');
});

/* ------------------------------------------------------------------ */
/* UTF-8 payloads                                                      */
/* ------------------------------------------------------------------ */

test('Cyrillic text is encoded as UTF-8 bytes, not UTF-16 code units', () => {
  const cyrillic = 'Контекст занято 48%';
  const byteLength = Buffer.byteLength(cyrillic, 'utf8');
  assert.equal(byteLength, 33, 'the fixture text must be 33 bytes in UTF-8');

  const result = qr.encodeQrMatrix(cyrillic);
  assertMatrixShape(result, 'cyrillic payload');
  assert.equal(result.text, cyrillic, 'the source text must be echoed back');

  const again = qr.encodeQrMatrix(cyrillic);
  assert.equal(
    JSON.stringify(result.modules),
    JSON.stringify(again.modules),
    'the Cyrillic matrix must be deterministic',
  );

  const sameCharLength = qr.encodeQrMatrix('a'.repeat(cyrillic.length));
  assert.notEqual(
    JSON.stringify(result.modules),
    JSON.stringify(sameCharLength.modules),
    'Cyrillic and same-length ASCII must not collide',
  );

  // Byte-driven version selection: 33 UTF-8 bytes must behave exactly like
  // 33 ASCII bytes and unlike the 19 UTF-16 code units of the same string.
  const sameByteLength = qr.encodeQrMatrix('a'.repeat(byteLength));
  assert.equal(
    result.version,
    sameByteLength.version,
    'the version must be selected from the UTF-8 byte length',
  );
});

/* ------------------------------------------------------------------ */
/* Capacity limits                                                     */
/* ------------------------------------------------------------------ */

test('empty text and overflow are reported through error codes', () => {
  assert.throws(
    () => qr.encodeQrMatrix(''),
    (error) => error instanceof Error && error.code === 'empty',
    'an empty payload must raise code "empty"',
  );

  assert.throws(
    () => qr.encodeQrMatrix('a'.repeat(214)),
    (error) => error instanceof Error && error.code === 'capacity',
    '214 bytes must not fit into V10-M and must raise code "capacity"',
  );

  const maxFit = qr.encodeQrMatrix('a'.repeat(213));
  assertMatrixShape(maxFit, '213-byte payload');
  assert.equal(maxFit.version, 10, '213 bytes must use the largest supported version');
});

/* ------------------------------------------------------------------ */
/* Mask range and render stability                                     */
/* ------------------------------------------------------------------ */

test('the mask stays in 0..7 and the SVG is stable across calls', () => {
  for (const text of QR_TEXTS) {
    const result = qr.encodeQrMatrix(text);
    assert.ok(
      Number.isInteger(result.mask) && result.mask >= 0 && result.mask <= 7,
      `mask ${result.mask} must be an integer in 0..7`,
    );
    const first = qr.renderQrSvg(text);
    const second = qr.renderQrSvg(text);
    assert.equal(first, second, 'rendering the same text twice must be identical');
  }
});

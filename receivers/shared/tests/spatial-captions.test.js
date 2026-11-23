"use strict";

const assert = require("node:assert/strict");
const captions = require("../pslink-spatial-captions.js");

function near(actual, expected, label) {
  assert.ok(
    Math.abs(actual - expected) < 0.001,
    `${label}: expected ${expected}, got ${actual}`
  );
}

// 1. Letterboxing: 16:9 video inside a 1000x600 viewport.
const rect = captions.computeContentRect(
  { left: 100, top: 50, width: 1000, height: 600 },
  1920,
  1080
);

assert.ok(rect, "Content rectangle should exist");
near(rect.left, 100, "Content left");
near(rect.top, 68.75, "Letterbox top");
near(rect.width, 1000, "Content width");
near(rect.height, 562.5, "Content height");

// 2. Spatial coordinates map into the displayed video rectangle.
const mapped = captions.mapLine(
  {
    text: "مرحبا",
    left: 0.1,
    top: 0.2,
    right: 0.5,
    bottom: 0.6,
    direction: "rtl"
  },
  rect
);

assert.ok(mapped, "Valid Arabic caption should map");
assert.equal(mapped.text, "مرحبا");
assert.equal(mapped.direction, "rtl");
near(mapped.left, 200, "Mapped left");
near(mapped.top, 181.25, "Mapped top");
near(mapped.width, 400, "Mapped width");
near(mapped.height, 225, "Mapped height");

// 3. LTR direction is preserved.
const ltr = captions.mapLine(
  {
    text: "Hello",
    left: 0,
    top: 0,
    right: 1,
    bottom: 1,
    direction: "ltr"
  },
  rect
);
assert.equal(ltr.direction, "ltr");

// 4. Invalid or empty captions are rejected.
assert.equal(captions.mapLine(null, rect), null);
assert.equal(captions.mapLine({ text: "   ", left: 0, top: 0, right: 1, bottom: 1 }, rect), null);
assert.equal(captions.mapLine({ text: "Invalid", left: 0.8, top: 0, right: 0.2, bottom: 1 }, rect), null);
assert.equal(captions.mapLine({ text: "Invalid", left: 0, top: 0.8, right: 1, bottom: 0.2 }, rect), null);

// 5. Invalid viewport dimensions are rejected.
assert.equal(
  captions.computeContentRect(
    { left: 0, top: 0, width: 0, height: 600 },
    1920,
    1080
  ),
  null
);

// 6. Coordinates outside normalized bounds are clamped.
const clamped = captions.mapLine(
  { text: "Clamp", left: -1, top: -1, right: 2, bottom: 2 },
  rect
);
assert.ok(clamped);
near(clamped.left, rect.left, "Clamped left");
near(clamped.top, rect.top, "Clamped top");
near(clamped.width, rect.width, "Clamped width");
near(clamped.height, rect.height, "Clamped height");

console.log("PASS: Letterbox geometry");
console.log("PASS: Spatial coordinate mapping");
console.log("PASS: Arabic RTL text mapping");
console.log("PASS: LTR direction preservation");
console.log("PASS: Invalid caption rejection");
console.log("PASS: Invalid viewport rejection");
console.log("PASS: Normalized coordinate clamping");
console.log("Spatial captions tests passed.");

/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";

const assert = require("node:assert/strict");
const Receiver = require("../pslink-client.js");

assert.equal(Receiver.normalizePairingCode("١٢٣-٤٥٦"), "123456");
assert.equal(Receiver.normalizePairingCode("۱۲۳۴۵۶"), "123456");
assert.equal(Receiver.normalizePairingCode("12a456"), null);
assert.equal(Receiver.normalizePairingCode("12345"), null);

function packet(type, flags, pts, arg1, arg2, payload) {
  const out = new ArrayBuffer(28 + payload.length);
  const view = new DataView(out);

  view.setUint32(0, 0x50534C4D, false);
  view.setUint8(4, 1);
  view.setUint8(5, type);
  view.setUint16(6, flags, false);

  const hi = Math.floor(pts / 4294967296);
  const lo = pts >>> 0;

  view.setUint32(8, hi, false);
  view.setUint32(12, lo, false);
  view.setInt32(16, arg1, false);
  view.setInt32(20, arg2, false);
  view.setUint32(24, payload.length, false);

  new Uint8Array(out, 28).set(payload);
  return out;
}

const video = Receiver.parseMediaPacket(
  packet(1, 1, 9000000000, 0, 0, [1, 2, 3])
);

assert.equal(video.type, "video");
assert.equal(video.keyFrame, true);
assert.equal(video.ptsNs, 9000000000);
assert.deepEqual(Array.from(video.payload), [1, 2, 3]);

const audio = Receiver.parseMediaPacket(
  packet(2, 0, 9000000001, 48000, 2, [1, 2, 3, 4])
);

assert.equal(audio.type, "audio");
assert.equal(audio.sampleRate, 48000);
assert.equal(audio.channels, 2);

assert.equal(
  Receiver.parseMediaPacket(packet(3, 0, 1, 0, 0, [1])),
  null
);

assert.equal(
  Receiver.parseMediaPacket(packet(2, 0, 1, 1, 2, [1, 2])),
  null
);

console.log("PS Link browser receiver protocol tests passed");

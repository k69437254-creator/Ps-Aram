/* PSLINK_XBOX_AUTOMATED_TESTS_V1 */
"use strict";

const assert = require("node:assert/strict");
const Receiver = require("../pslink-client.js");

function test(name, fn) {
  fn();
  console.log("PASS:", name);
}

test("Arabic and Persian pairing digits", () => {
  assert.equal(Receiver.normalizePairingCode("١٢٣٤٥٦"), "123456");
  assert.equal(Receiver.normalizePairingCode("۱۲۳۴۵۶"), "123456");
  assert.equal(Receiver.normalizePairingCode("123-456"), "123456");
});

test("Invalid pairing codes", () => {
  assert.equal(Receiver.normalizePairingCode("12345"), null);
  assert.equal(Receiver.normalizePairingCode("12AB56"), null);
});

test("HTTPS hostname resolves to WSS", () => {
  assert.equal(
    Receiver.resolveSocketUrl("tv.example.test", {protocol:"https:"}),
    "wss://tv.example.test/subtitle"
  );
});

test("HTTP local host resolves to WS port 8765", () => {
  assert.equal(
    Receiver.resolveSocketUrl("192.168.1.20", {protocol:"http:"}),
    "ws://192.168.1.20:8765/subtitle"
  );
});

test("HTTPS rejects insecure WS", () => {
  assert.throws(
    () => Receiver.resolveSocketUrl(
      "ws://tv.example.test/subtitle",
      {protocol:"https:"}
    ),
    /HTTPS/
  );
});

test("Explicit WSS endpoint is preserved", () => {
  assert.equal(
    Receiver.resolveSocketUrl(
      "wss://tv.example.test/subtitle",
      {protocol:"https:"}
    ),
    "wss://tv.example.test/subtitle"
  );
});

test("HTTPS HLS URL uses same-origin proxy", () => {
  assert.equal(
    Receiver.normalizeStreamUrl(
      "http://192.168.1.20:8765/hls/index.m3u8",
      {protocol:"https:",origin:"https://tv.example.test"}
    ),
    "https://tv.example.test/hls/index.m3u8"
  );
});

test("HTTPS rejects insecure non-HLS URL", () => {
  assert.equal(
    Receiver.normalizeStreamUrl(
      "http://example.test/private",
      {protocol:"https:",origin:"https://tv.example.test"}
    ),
    null
  );
});

test("Unsupported stream protocol is rejected", () => {
  assert.equal(
    Receiver.normalizeStreamUrl("javascript:alert(1)", null),
    null
  );
});

console.log("Xbox URL and pairing tests passed.");

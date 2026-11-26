/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const root = path.resolve(__dirname, "..");
const hlsCode = fs.readFileSync(path.join(root, "vendor/hls.min.js"), "utf8");
const context = { console, URL, CustomEvent: function (type, options) { this.type = type; this.detail = options && options.detail; } };
context.window = context;
context.self = context;
context.dispatchEvent = function () {};
vm.createContext(context);
vm.runInContext(hlsCode, context, { filename: "hls.min.js" });
assert.ok(context.Hls, "Bundled Hls.js must export Hls on the browser global");
assert.equal(typeof context.Hls.isSupported, "function");
assert.equal(context.Hls.version, "1.5.13");

// HLS.js itself is loaded and version-checked above. This double isolates the
// PS Link player adapter from browser MediaSource/SourceBuffer APIs unavailable in Node.
const bundledHls = context.Hls;
const instances = [];
function HlsAdapterDouble(options) {
  this.options = options; this.loaded = null; this.media = null;
  this.handlers = {}; this.destroyed = false; instances.push(this);
}
HlsAdapterDouble.version = bundledHls.version;
HlsAdapterDouble.isSupported = function () { return true; };
HlsAdapterDouble.Events = { MANIFEST_PARSED: "manifestParsed", ERROR: "error" };
HlsAdapterDouble.prototype.loadSource = function (url) { this.loaded = url; };
HlsAdapterDouble.prototype.attachMedia = function (media) { this.media = media; };
HlsAdapterDouble.prototype.on = function (name, callback) { this.handlers[name] = callback; };
HlsAdapterDouble.prototype.destroy = function () { this.destroyed = true; };
context.Hls = HlsAdapterDouble;
const video = {
  canPlayType: function () { return ""; }, addEventListener: function () {},
  removeEventListener: function () {}, removeAttribute: function () {},
  pause: function () {}, load: function () {}, play: function () { return Promise.resolve(); }
};
context.document = { getElementById: function (id) { return id === "game-video" ? video : null; } };
vm.runInContext(fs.readFileSync(path.join(root, "media-player.js"), "utf8"), context, { filename: "media-player.js" });
assert.equal(typeof context.PSLinkMediaPlayer, "function");
const player = context.PSLinkMediaPlayer();
assert.throws(function () { player.start("not-a-url"); }, /غير صالح|invalid/i);
const validUrl = "http://example.com/stream.m3u8";
player.start(validUrl);
assert.ok(player.hls);
assert.equal(player.hls.loaded, validUrl);
assert.equal(player.hls.media, video);
const first = player.hls;
player.stop();
assert.equal(first.destroyed, true);
assert.equal(player.hls, null);
const secondPlayer = context.PSLinkMediaPlayer();
secondPlayer.start(validUrl);
const previous = secondPlayer.hls;
secondPlayer.start(validUrl);
assert.ok(secondPlayer.hls);
assert.notEqual(secondPlayer.hls, previous);
assert.equal(previous.destroyed, true);
secondPlayer.stop();

// Verify NotAllowedError triggers muted autoplay fallback with warning event.
(async function verifyMutedFallback() {
  const warned = [];
  context.dispatchEvent = function (ev) {
    if (ev && ev.type === "pslink-media-warning") warned.push(ev.detail);
  };
  let attempts = 0;
  video.muted = false;
  video.play = function () {
    attempts += 1;
    if (attempts === 1) {
      const err = new Error("user gesture required");
      err.name = "NotAllowedError";
      return Promise.reject(err);
    }
    return Promise.resolve();
  };
  const fallbackPlayer = context.PSLinkMediaPlayer();
  fallbackPlayer.start(validUrl);
  fallbackPlayer.hls.handlers[context.Hls.Events.MANIFEST_PARSED]();
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(attempts, 2);
  assert.equal(video.muted, true);
  assert.equal(warned.length, 1);
  fallbackPlayer.stop();
  console.log("PS Link HLS.js/player adapter integration tests passed");
})().catch(err => {
  console.error(err);
  process.exitCode = 1;
});

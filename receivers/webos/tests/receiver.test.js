/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

async function main() {
  const events = [];
  const calls = [];
  const listeners = new Map();
  let playResult = Promise.resolve();
  const video = {
    error: null,
    addEventListener(name, callback) { listeners.set(name, callback); },
    removeEventListener(name, callback) {
      if (listeners.get(name) === callback) listeners.delete(name);
    },
    load() { calls.push(["load"]); },
    play() { calls.push(["play"]); return playResult; },
    pause() { calls.push(["pause"]); },
    removeAttribute(name) { calls.push(["removeAttribute", name]); }
  };
  const document = {
    body: { getAttribute: () => "webos" },
    getElementById(id) { return id === "game-video" ? video : null; }
  };
  const window = {
    dispatchEvent(event) { events.push(event); }
  };
  class CustomEvent {
    constructor(type, options) {
      this.type = type;
      this.detail = options.detail;
    }
  }

  const context = { window, document, URL, CustomEvent };
  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(path.join(__dirname, "../media-player.js"), "utf8"),
    context,
    { filename: "webos/media-player.js" }
  );

  const player = window.PSLinkMediaPlayer();
  assert.throws(() => player.start("javascript:alert(1)"), /غير صالح/);
  player.start("https://phone.local/hls/index.m3u8");
  assert.equal(video.src, "https://phone.local/hls/index.m3u8");
  assert.equal(video.autoplay, true);
  assert.equal(video.playsInline, true);
  assert.equal(video.volume, 1);
  assert.equal(listeners.has("error"), true);
  assert.deepEqual(calls.slice(-2), [["load"], ["play"]]);
  await Promise.resolve();

  const onError = listeners.get("error");
  player.stop();
  assert.equal(listeners.has("error"), false);
  assert.deepEqual(calls.slice(-3), [
    ["pause"],
    ["removeAttribute", "src"],
    ["load"]
  ]);
  onError();
  assert.equal(events.length, 0, "stale media errors must be ignored after stop");

  playResult = Promise.reject(new Error("autoplay denied"));
  player.start("http://phone.local/hls/index.m3u8");
  await Promise.resolve();
  assert(events.some(event => event.type === "pslink-media-error" &&
    event.detail === "autoplay denied"));

  player.stop();
  console.log("webOS HTML media lifecycle tests passed.");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const events = [];
const calls = [];
let avPlayer;
let prepareSuccess;
let prepareFailure;

const avplay = {
  setListener(listener) { this.listener = listener; },
  open(url) { calls.push(["open", url]); },
  setDisplayRect(...args) { calls.push(["rect", ...args]); },
  setDisplayMethod(mode) { calls.push(["display", mode]); },
  prepareAsync(success, failure) {
    prepareSuccess = success;
    prepareFailure = failure;
  },
  play() { calls.push(["play"]); },
  stop() { calls.push(["stop"]); },
  close() { calls.push(["close"]); }
};

const stage = {
  appendChild(node) {
    avPlayer = node;
  }
};
const document = {
  body: { getAttribute: () => "tizen" },
  getElementById(id) {
    if (id === "av-player") return avPlayer || null;
    if (id === "video-stage") return stage;
    return null;
  },
  createElement() {
    return {};
  }
};
const window = {
  webapis: { avplay },
  innerWidth: 1920,
  innerHeight: 1080,
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
  { filename: "tizen/media-player.js" }
);

const player = window.PSLinkMediaPlayer();
assert.throws(() => player.start("javascript:alert(1)"), /غير صالح/);
player.start("http://phone.local/hls/index.m3u8");
assert.deepEqual(calls.filter(([name]) =>
  ["open", "rect", "display"].includes(name)), [
  ["open", "http://phone.local/hls/index.m3u8"],
  ["rect", 0, 0, 1920, 1080],
  ["display", "PLAYER_DISPLAY_MODE_LETTER_BOX"]
]);
assert.equal(typeof prepareSuccess, "function");
prepareSuccess();
assert.equal(player.active, true);
assert.deepEqual(calls[calls.length - 1], ["play"]);

player.stop();
assert.equal(player.active, false);
assert(calls.some(([name]) => name === "stop"));
assert(calls.some(([name]) => name === "close"));

player.start("https://phone.local/stream.m3u8");
const stalePrepareSuccess = prepareSuccess;
player.stop();
stalePrepareSuccess();
assert.equal(calls.filter(([name]) => name === "play").length, 1);

player.start("https://phone.local/stream.m3u8");
prepareFailure("prepare failed");
assert(events.some(event => event.type === "pslink-media-error" &&
  event.detail === "prepare failed"));

delete window.webapis;
assert.throws(() => player.start("https://phone.local/stream.m3u8"), /AVPlay/);
console.log("Tizen AVPlay lifecycle tests passed.");

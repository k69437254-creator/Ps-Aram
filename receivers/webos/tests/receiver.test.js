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
  assert.equal(listeners.has("waiting"), true);
  assert.equal(listeners.has("playing"), true);
  assert.equal(listeners.has("ended"), true);

  listeners.get("waiting")();
  listeners.get("playing")();

  assert(events.some(event =>
    event.type === "pslink-media-buffering" &&
    event.detail.active === true
  ));
  assert(events.some(event =>
    event.type === "pslink-media-buffering" &&
    event.detail.active === false
  ));

  assert.deepEqual(calls.slice(-2), [["load"], ["play"]]);
  await Promise.resolve();

  const onError = listeners.get("error");
  const onWaiting = listeners.get("waiting");
  const eventCountBeforeStop = events.length;

  player.stop();
  ["error", "waiting", "playing", "ended"].forEach(name => {
    assert.equal(listeners.has(name), false, name + " listener must be removed");
  });
  assert.deepEqual(calls.slice(-3), [
    ["pause"],
    ["removeAttribute", "src"],
    ["load"]
  ]);
  onError();
  onWaiting();
  assert.equal(
    events.length,
    eventCountBeforeStop,
    "stale media callbacks must be ignored after stop"
  );

  playResult = Promise.reject(new Error("autoplay denied"));
  player.start("http://phone.local/hls/index.m3u8");
  await Promise.resolve();
  assert(events.some(event => event.type === "pslink-media-error" &&
    event.detail === "autoplay denied"));

  player.stop();

  playResult = Promise.resolve();
  player.start("https://phone.local/hls/ended.m3u8");
  const onEnded = listeners.get("ended");
  assert.equal(typeof onEnded, "function");
  onEnded();

  assert(events.some(event => event.type === "pslink-media-ended"),
    "completed playback must emit an ended event");

  player.stop();
  console.log("webOS HTML media lifecycle tests passed.");
  console.log("webOS buffering and completion tests passed.");
  console.log("webOS stale callback tests passed.");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});


// PSLINK_WEBOS_LIFECYCLE_TESTS_V1
{
  const app = fs.readFileSync(path.join(__dirname, "../app.js"), "utf8");
  const playerSource = fs.readFileSync(
    path.join(__dirname, "../media-player.js"), "utf8"
  );
  const cssSource = fs.readFileSync(path.join(__dirname, "../app.css"), "utf8");

  assert(app.includes("PSLINK_WEBOS_LIFECYCLE_REPAIR_V1"));
  assert(
  /onReady:\s*function\s*\(ready\)\s*\{[\s\S]*?if\s*\(!ready\)\s*resetPlayback\(\);/.test(app),
  "connection loss must reset playback"
);
  assert(app.includes('window.addEventListener("pslink-media-ended"'));
  assert(app.includes('window.addEventListener("pslink-media-buffering"'));
  assert(app.includes("setPlaybackMode(true)"));
  assert(app.includes("captionsNode.textContent = \"\";"));

  assert(playerSource.includes("PSLINK_WEBOS_MEDIA_EVENTS_V1"));
  for (const eventName of ["waiting", "playing", "ended", "error"]) {
    assert(playerSource.includes(eventName + ": function"));
  }

  assert(cssSource.includes("PSLINK_WEBOS_PLAYBACK_MODE_V1"));
  assert(cssSource.includes("body.is-playing"));

  console.log("webOS app lifecycle contract tests passed.");
}

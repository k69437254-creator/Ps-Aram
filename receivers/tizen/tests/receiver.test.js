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


// PSLINK_TIZEN_APP_STREAMING_LIFECYCLE_TESTS_V1
// Exercise app.js event wiring independently from the AVPlay unit tests.
const appNodes = Object.create(null);
let appDocument;

function makeAppNode(id) {
  return {
    id,
    hidden: false,
    disabled: false,
    value: "",
    textContent: "",
    attributes: Object.create(null),
    listeners: Object.create(null),
    addEventListener(type, handler) {
      this.listeners[type] = handler;
    },
    setAttribute(name, value) {
      this.attributes[name] = value;
    },
    focus() {
      appDocument.activeElement = this;
    }
  };
}

[
  "status", "pairing-form", "connect", "disconnect",
  "subtitle", "captions", "media-notice",
  "playback-toolbar", "playback-status", "playback-disconnect",
  "host", "code", "game", "source", "translation", "empty-subtitle"
].forEach(id => { appNodes[id] = makeAppNode(id); });

const appClasses = new Set();
const appDocumentBody = {
  classList: {
    add(name) { appClasses.add(name); },
    remove(name) { appClasses.delete(name); },
    contains(name) { return appClasses.has(name); }
  }
};

appDocument = {
  body: appDocumentBody,
  activeElement: null,
  getElementById(id) {
    return appNodes[id] || null;
  }
};

const appWindowEvents = Object.create(null);
const appReceivers = [];
const appPlayer = {
  starts: [],
  stops: 0,
  throwOnStart: false,
  start(url) {
    if (this.throwOnStart) throw new Error("simulated AVPlay start failure");
    this.starts.push(url);
  },
  stop() {
    this.stops += 1;
  }
};

function FakeMediaPlayer() {
  return appPlayer;
}

function FakePsLinkReceiver(options) {
  this.options = options;
  this.disconnectCalls = 0;
  this.connect = (host, code) => {
    this.host = host;
    this.code = code;
    options.onReady(false);
  };
  this.disconnect = () => {
    this.disconnectCalls += 1;
    options.onReady(false);
  };
  appReceivers.push(this);
}

const appWindow = {
  PSLinkMediaPlayer: FakeMediaPlayer,
  PSLinkReceiver: FakePsLinkReceiver,
  PSLinkSpatialCaptions: {
    clear() {},
    render() { return false; }
  },
  addEventListener(name, handler) {
    appWindowEvents[name] = handler;
  }
};

vm.runInNewContext(
  fs.readFileSync(path.join(__dirname, "../app.js"), "utf8"),
  { window: appWindow, document: appDocument },
  { filename: "tizen/app.js" }
);

function submitAppPairing() {
  appNodes.host.value = "192.168.1.20";
  appNodes.code.value = "123456";
  const handler = appNodes["pairing-form"].listeners.submit;
  assert.equal(typeof handler, "function");
  handler({ preventDefault() {} });
  return appReceivers[appReceivers.length - 1];
}

const firstAppReceiver = submitAppPairing();
assert(firstAppReceiver);
firstAppReceiver.options.onReady(true);

const testStreamUrl = "http://192.168.1.20:8765/hls/test-token/index.m3u8";
firstAppReceiver.options.onStreamUrl({ url: testStreamUrl });

assert.equal(appPlayer.starts[appPlayer.starts.length - 1], testStreamUrl);
assert.equal(appClasses.has("is-playing"), true);
assert.equal(appNodes["playback-toolbar"].hidden, false);

// Losing the authenticated WebSocket must stop stale HLS playback.
const stopsBeforeDisconnect = appPlayer.stops;
firstAppReceiver.options.onReady(false);
assert(appPlayer.stops > stopsBeforeDisconnect);
assert.equal(appClasses.has("is-playing"), false);
assert.equal(appNodes["playback-toolbar"].hidden, true);

// A synchronous AVPlay start failure must restore the pairing UI.
firstAppReceiver.options.onReady(true);
appPlayer.throwOnStart = true;
firstAppReceiver.options.onStreamUrl({ url: testStreamUrl });
appPlayer.throwOnStart = false;
assert.equal(appClasses.has("is-playing"), false);
assert.equal(appNodes["playback-toolbar"].hidden, true);
assert.equal(appNodes["media-notice"].hidden, false);

// An asynchronous AVPlay error must also stop playback and exit playback mode.
firstAppReceiver.options.onReady(true);
firstAppReceiver.options.onStreamUrl({ url: testStreamUrl });
assert.equal(appClasses.has("is-playing"), true);
appWindowEvents["pslink-media-error"]({ detail: "AVPlay prepare failed" });
assert.equal(appClasses.has("is-playing"), false);
assert.equal(appNodes["playback-toolbar"].hidden, true);
assert.equal(appNodes["media-notice"].hidden, false);

// The playback toolbar button must perform a real disconnect.
firstAppReceiver.options.onReady(true);
firstAppReceiver.options.onStreamUrl({ url: testStreamUrl });
appNodes["playback-disconnect"].listeners.click();
assert(firstAppReceiver.disconnectCalls >= 1);
assert.equal(appClasses.has("is-playing"), false);

// Samsung TV remote Back key (10009) must disconnect the receiver.
const secondAppReceiver = submitAppPairing();
secondAppReceiver.options.onReady(true);
secondAppReceiver.options.onStreamUrl({ url: testStreamUrl });

let backPrevented = false;
appWindowEvents.keydown({
  key: "Back",
  keyCode: 10009,
  preventDefault() { backPrevented = true; }
});

assert.equal(backPrevented, true);
assert.equal(secondAppReceiver.disconnectCalls, 1);
assert.equal(appClasses.has("is-playing"), false);
assert.equal(appNodes["playback-toolbar"].hidden, true);

console.log("Tizen app streaming lifecycle tests passed.");


// PSLINK_TIZEN_AVPLAY_COMPLETION_TESTS_V1
// The preceding legacy test intentionally removes this API.
window.webapis = { avplay };
// Verify AVPlay buffering, completion, errors and stale-generation protection.
assert.equal(typeof avplay.listener.onbufferingstart, "function");
assert.equal(typeof avplay.listener.onbufferingcomplete, "function");
assert.equal(typeof avplay.listener.onstreamcompleted, "function");

const completionEventsBefore = events.length;

player.start("https://phone.local/hls/completion-test.m3u8");
const completionListener = avplay.listener;

completionListener.onbufferingstart();
assert(events.some(event =>
  event.type === "pslink-media-buffering" &&
  event.detail.active === true
));

completionListener.onbufferingcomplete();
assert(events.some(event =>
  event.type === "pslink-media-buffering" &&
  event.detail.active === false
));

prepareSuccess();
assert.equal(player.active, true);

completionListener.onstreamcompleted();
assert.equal(player.active, false);
assert(events.some(event =>
  event.type === "pslink-media-ended"
));

// An obsolete callback must not terminate a newer playback generation.
player.start("https://phone.local/hls/new-session.m3u8");
const oldCompletionListener = completionListener;
const newCompletionListener = avplay.listener;

prepareSuccess();
assert.equal(player.active, true);

const eventCountBeforeStaleCallback = events.length;
oldCompletionListener.onstreamcompleted();

assert.equal(player.active, true);
assert.equal(events.length, eventCountBeforeStaleCallback);

// Runtime AVPlay errors must clear active state and emit an error.
newCompletionListener.onerror("simulated runtime error");
assert.equal(player.active, false);
assert(events.some(event =>
  event.type === "pslink-media-error" &&
  event.detail === "simulated runtime error"
));

assert(events.length > completionEventsBefore);
console.log("Tizen AVPlay completion and buffering tests passed.");

// Application integration: completion returns to pairing mode.
assert.equal(typeof appWindowEvents["pslink-media-ended"], "function");
assert.equal(typeof appWindowEvents["pslink-media-buffering"], "function");

const completionReceiver = submitAppPairing();
completionReceiver.options.onReady(true);
completionReceiver.options.onStreamUrl({ url: testStreamUrl });

assert.equal(appClasses.has("is-playing"), true);

appWindowEvents["pslink-media-buffering"]({
  detail: { active: true }
});
assert.equal(appNodes.status.textContent, "جارٍ تحميل بيانات الفيديو…");

appPlayer.active = true;
appWindowEvents["pslink-media-buffering"]({
  detail: { active: false }
});
assert.equal(appNodes.status.textContent, "الفيديو والصوت قيد التشغيل");

const stopsBeforeNaturalCompletion = appPlayer.stops;
appWindowEvents["pslink-media-ended"]({
  detail: "اكتمل بث الوسائط"
});

assert(appPlayer.stops > stopsBeforeNaturalCompletion);
assert.equal(appClasses.has("is-playing"), false);
assert.equal(appNodes["playback-toolbar"].hidden, true);
assert.equal(appNodes.status.textContent, "اكتمل بث الوسائط");

console.log("Tizen app completion and buffering integration tests passed.");

// PSLINK_TIZEN_SPATIAL_REMOTE_TESTS_V1
// Spatial caption geometry tests.
const spatial = require("../shared/pslink-spatial-captions.js");

const contentRect = spatial.computeContentRect(
  { left: 0, top: 0, width: 1000, height: 1000 },
  1920,
  1080
);

assert(contentRect);
assert.ok(Math.abs(contentRect.left - 0) < 1e-8, "contentRect.left");
assert.ok(Math.abs(contentRect.width - 1000) < 1e-8, "contentRect.width");
assert.ok(Math.abs(contentRect.height - 562.5) < 1e-8, "contentRect.height");
assert.ok(Math.abs(contentRect.top - 218.75) < 1e-8, "contentRect.top");

const mappedCaption = spatial.mapLine(
  {
    text: "مرحبا",
    left: 0.1,
    top: 0.2,
    right: 0.5,
    bottom: 0.4,
    direction: "rtl"
  },
  contentRect
);

assert(mappedCaption);
assert.equal(mappedCaption.text, "مرحبا");
assert.ok(Math.abs(mappedCaption.left - 100) < 1e-8, "mappedCaption.left");
assert.ok(Math.abs(mappedCaption.top - 331.25) < 1e-8, "mappedCaption.top");
assert.ok(Math.abs(mappedCaption.width - 400) < 1e-8, "mappedCaption.width");
assert.equal(mappedCaption.direction, "rtl");

assert.equal(
  spatial.mapLine(
    { text: "invalid", left: 0.8, right: 0.2, top: 0, bottom: 1 },
    contentRect
  ),
  null
);

assert.equal(
  spatial.computeContentRect(
    { left: 0, top: 0, width: 0, height: 100 },
    1920,
    1080
  ),
  null
);

// Verify spatial rendering is gated by an accepted stream URL.
const appSourceForSpatialTests = fs.readFileSync(
  path.join(__dirname, "../app.js"),
  "utf8"
);

assert.match(
  appSourceForSpatialTests,
  /if \(streamUrlReceived && window\.PSLinkSpatialCaptions\)/
);
assert.match(
  appSourceForSpatialTests,
  /captionsNode\.textContent = "";/
);
assert.match(
  appSourceForSpatialTests,
  /PSLINK_SPATIAL_CAPTIONS_INTEGRATION_V3/
);

// Exercise the shared TV remote navigation module with a small DOM mock.
const remoteListeners = Object.create(null);
const remoteElements = [];

function makeRemoteElement(tagName, left, top) {
  return {
    tagName,
    offsetWidth: 100,
    offsetHeight: 50,
    focused: false,
    clicked: 0,
    getBoundingClientRect() {
      return { left, top, width: 100, height: 50 };
    },
    focus() {
      remoteDocument.activeElement = this;
      this.focused = true;
    },
    click() {
      this.clicked += 1;
    }
  };
}

const remoteButtonA = makeRemoteElement("BUTTON", 0, 0);
const remoteButtonB = makeRemoteElement("BUTTON", 150, 0);
const remoteButtonC = makeRemoteElement("BUTTON", 0, 100);

remoteElements.push(remoteButtonA, remoteButtonB, remoteButtonC);

const remoteDocument = {
  activeElement: null,
  querySelectorAll() {
    return remoteElements;
  },
  addEventListener(type, callback) {
    remoteListeners[type] = callback;
  }
};

const remoteWindow = {
  addEventListener(type, callback) {
    remoteListeners["window:" + type] = callback;
  }
};

const remoteContext = {
  document: remoteDocument,
  window: remoteWindow
};

vm.runInNewContext(
  fs.readFileSync(path.join(__dirname, "../shared/tv-remote.js"), "utf8"),
  remoteContext,
  { filename: "tizen/shared/tv-remote.js" }
);

remoteListeners["window:load"]();
assert.equal(remoteDocument.activeElement, remoteButtonA);

let rightPrevented = false;
remoteListeners.keydown({
  key: "ArrowRight",
  preventDefault() {
    rightPrevented = true;
  }
});

assert.equal(rightPrevented, true);
assert.equal(remoteDocument.activeElement, remoteButtonB);

let enterPrevented = false;
remoteListeners.keydown({
  key: "Enter",
  preventDefault() {
    enterPrevented = true;
  }
});

assert.equal(enterPrevented, true);
assert.equal(remoteButtonB.clicked, 1);

let downPrevented = false;
remoteListeners.keydown({
  key: "ArrowDown",
  preventDefault() {
    downPrevented = true;
  }
});

assert.equal(downPrevented, true);
assert.equal(remoteDocument.activeElement, remoteButtonC);

console.log("Tizen spatial caption geometry tests passed.");
console.log("Tizen remote navigation tests passed.");

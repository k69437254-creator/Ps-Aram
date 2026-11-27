/* PSLINK_XBOX_APP_LIFECYCLE_TESTS_V1 */
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");

const appPath = path.join(__dirname, "..", "app.js");
const source = fs.readFileSync(appPath, "utf8");

class Element {
  constructor(id) {
    this.id = id;
    this.listeners = {};
    this.textContent = "";
    this.value = "";
    this.disabled = false;
    this.paused = true;
    this.muted = false;
    this.volume = 1;
    this.src = "";
  }
  addEventListener(name, fn) {
    (this.listeners[name] ||= []).push(fn);
  }
  fire(name, event = {}) {
    for (const fn of this.listeners[name] || []) fn(event);
  }
  removeAttribute(name) {
    if (name === "src") this.src = "";
  }
  pause() { this.paused = true; }
  load() {}
  play() {
    this.paused = false;
    return Promise.resolve();
  }
  canPlayType() { return "probably"; }
}

const ids = [
  "video", "status", "pairing-form", "connect", "disconnect",
  "host", "code", "captions", "game", "translation"
];

const elements = Object.fromEntries(ids.map(id => [id, new Element(id)]));

const receivers = [];
class FakeReceiver {
  constructor(callbacks) {
    this.callbacks = callbacks;
    this.connected = false;
    this.disconnected = false;
    receivers.push(this);
  }
  connect(host, code) {
    this.connected = true;
    this.host = host;
    this.code = code;
  }
  disconnect() {
    this.disconnected = true;
  }
}

const hlsInstances = [];

class FakeHls {
  constructor() {
    this.handlers = {};
    this.destroyed = false;
    this.startLoadCalls = 0;
    this.recoverCalls = 0;
    hlsInstances.push(this);
  }
  on(name, callback) { this.handlers[name] = callback; }
  emit(name, data) {
    if (this.handlers[name]) this.handlers[name](name, data);
  }
  loadSource(url) { this.url = url; }
  attachMedia(video) { this.video = video; }
  destroy() { this.destroyed = true; }
  startLoad() { this.startLoadCalls++; }
  recoverMediaError() { this.recoverCalls++; }
  static isSupported() { return true; }
}

FakeHls.Events = {
  MANIFEST_PARSED: "manifest",
  ERROR: "error"
};
FakeHls.ErrorTypes = {
  NETWORK_ERROR: "networkError",
  MEDIA_ERROR: "mediaError"
};

const spatial = {
  clearCalls: 0,
  clear() { this.clearCalls++; },
  render() { return false; }
};

const windowObject = {
  PSLinkReceiver: FakeReceiver,
  PSLinkSpatialCaptions: spatial,
  Hls: FakeHls
};

const context = {
  window: windowObject,
  Hls: FakeHls,
  document: {
    getElementById(id) { return elements[id]; }
  },
  console,
  Promise,
  Array,
  String
};

vm.runInNewContext(source, context, { filename: appPath });

function submit(host, code) {
  elements.host.value = host;
  elements.code.value = code;
  elements["pairing-form"].fire("submit", {
    preventDefault() {}
  });
}

async function main() {
  submit("192.168.1.20", "123456");
  assert.equal(receivers.length, 1);
  assert.equal(receivers[0].connected, true);
  assert.equal(elements.disconnect.disabled, false);

  const first = receivers[0];
  first.callbacks.onStreamUrl({ url: "https://example.test/first.m3u8" });
  const firstHls = hlsInstances.at(-1);
  firstHls.emit("manifest");
  assert.equal(firstHls.url, "https://example.test/first.m3u8");

  submit("192.168.1.21", "654321");
  assert.equal(first.disconnected, true);
  assert.equal(firstHls.destroyed, true);
  assert.equal(receivers.length, 2);

  const second = receivers[1];
  const countBeforeStaleStream = hlsInstances.length;

  first.callbacks.onStreamUrl({ url: "https://example.test/stale.m3u8" });
  first.callbacks.onSubtitle({ game: "OLD", translation: "قديم" });

  assert.equal(hlsInstances.length, countBeforeStaleStream);
  assert.notEqual(elements.game.textContent, "OLD");

  second.callbacks.onStreamUrl({ url: "https://example.test/second.m3u8" });
  const secondHls = hlsInstances.at(-1);

  elements.video.fire("waiting");
  assert.match(elements.status.textContent, /تحميل البث/);

  secondHls.emit("manifest");
  elements.video.fire("playing");
  assert.equal(elements.status.textContent, "البث يعمل");

  secondHls.emit("error", {
    fatal: true,
    type: FakeHls.ErrorTypes.NETWORK_ERROR
  });
  assert.equal(secondHls.startLoadCalls, 1);
  assert.equal(secondHls.destroyed, false);

  secondHls.emit("error", {
    fatal: true,
    type: FakeHls.ErrorTypes.MEDIA_ERROR
  });
  assert.equal(secondHls.recoverCalls, 1);

  secondHls.emit("error", {
    fatal: true,
    type: FakeHls.ErrorTypes.MEDIA_ERROR
  });
  assert.equal(secondHls.destroyed, true);
  assert.match(elements.status.textContent, /تعذر تشغيل بث HLS/);

  second.callbacks.onStreamUrl({ url: "https://example.test/third.m3u8" });
  const thirdHls = hlsInstances.at(-1);
  thirdHls.emit("manifest");

  elements.video.fire("ended");
  assert.equal(elements.status.textContent, "انتهى البث");

  second.callbacks.onStreamUrl({ url: "https://example.test/fourth.m3u8" });
  const fourthHls = hlsInstances.at(-1);

  elements["disconnect"].fire("click");
  assert.equal(second.disconnected, true);
  assert.equal(fourthHls.destroyed, true);
  assert.equal(elements.disconnect.disabled, true);
  assert.equal(elements.connect.disabled, false);
  assert.equal(elements.status.textContent, "تم قطع الاتصال");

  second.callbacks.onStreamUrl({ url: "https://example.test/stale-after-disconnect.m3u8" });
  assert.equal(hlsInstances.at(-1), fourthHls);

  console.log("PASS: Xbox connection replacement and stale callback isolation.");
  console.log("PASS: Xbox HLS stream replacement and cleanup.");
  console.log("PASS: Xbox video waiting and playing events.");
  console.log("PASS: Xbox bounded HLS network and media recovery.");
  console.log("PASS: Xbox fatal HLS error cleanup.");
  console.log("PASS: Xbox video ended state.");
  console.log("PASS: Xbox disconnect cleanup and stale stream rejection.");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

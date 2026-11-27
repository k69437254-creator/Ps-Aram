/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

class FakeNode {
  constructor(id) {
    this.id = id;
    this.disabled = false;
    this.hidden = false;
    this.textContent = "";
    this.value = "";
    this.attributes = {};
    this.listeners = {};
  }

  addEventListener(name, callback) {
    this.listeners[name] = callback;
  }

  setAttribute(name, value) {
    this.attributes[name] = value;
  }

  fire(name, event = {}) {
    const callback = this.listeners[name];
    if (callback) callback(event);
  }
}

const ids = [
  "status", "pairing-form", "connect", "disconnect",
  "subtitle", "captions", "media-notice", "host", "code",
  "game", "source", "translation", "empty-subtitle"
];

const nodes = Object.fromEntries(ids.map(id => [id, new FakeNode(id)]));
const windowListeners = {};
const receivers = [];
let playerStops = 0;
let playerStarts = [];
let captionClears = 0;
let failPlayerStop = false;
let failCaptionClear = false;

class FakeReceiver {
  constructor(options) {
    this.options = options;
    this.connected = false;
    this.disconnected = false;
    this.disconnectCalls = 0;
    this.failDisconnect = false;
    receivers.push(this);
  }

  connect(host, code) {
    this.connected = true;
    this.host = host;
    this.code = code;
  }

  disconnect() {
    this.disconnectCalls += 1;
    if (this.failDisconnect) {
      throw new Error("simulated receiver cleanup failure");
    }
    this.disconnected = true;
  }
}

const windowObject = {
  PSLinkReceiver: FakeReceiver,

  PSLinkMediaPlayer: function () {
    return {
      start(url) {
        playerStarts.push(url);
      },
      stop() {
        playerStops += 1;
        if (failPlayerStop) {
          throw new Error("simulated player cleanup failure");
        }
      }
    };
  },

  PSLinkSpatialCaptions: {
    render() {
      return false;
    },
    clear() {
      captionClears += 1;
      if (failCaptionClear) {
        throw new Error("simulated caption cleanup failure");
      }
    }
  },

  addEventListener(name, callback) {
    windowListeners[name] = callback;
  }
};

const documentObject = {
  getElementById(id) {
    return nodes[id] || null;
  }
};

const context = {
  window: windowObject,
  document: documentObject,
  console,
  String,
  Array,
  Number
};

vm.createContext(context);

const appPath = path.join(__dirname, "..", "app.js");
vm.runInContext(fs.readFileSync(appPath, "utf8"), context, {
  filename: "app.js"
});

function submit() {
  let prevented = false;
  nodes["pairing-form"].fire("submit", {
    preventDefault() {
      prevented = true;
    }
  });
  assert.equal(prevented, true);
}

function provideCredentials() {
  nodes.host.value = "192.168.1.20";
  nodes.code.value = "123456";
}

provideCredentials();

assert.equal(nodes.connect.disabled, false);
assert.equal(nodes.disconnect.disabled, true);
assert.equal(nodes.host.disabled, false);
assert.equal(nodes.code.disabled, false);

// First connection enters the active state immediately.
submit();

assert.equal(receivers.length, 1);
assert.equal(receivers[0].connected, true);
assert.equal(nodes.connect.disabled, true);
assert.equal(nodes.disconnect.disabled, false);
assert.equal(nodes.host.disabled, true);
assert.equal(nodes.code.disabled, true);

// A not-ready callback must not re-enable connection controls.
receivers[0].options.onReady(false);
assert.equal(nodes.connect.disabled, true);
assert.equal(nodes.disconnect.disabled, false);

// Duplicate submit must not create another receiver.
submit();
assert.equal(receivers.length, 1);

// Verify subtitle display and its cleanup.
receivers[0].options.onSubtitle({
  game: "Test Game",
  source: "Original",
  translation: "Translated"
});

assert.equal(nodes.subtitle.hidden, false);
assert.equal(nodes.translation.textContent, "Translated");

// Disconnect must close the receiver and reset the complete UI.
nodes.disconnect.fire("click");

assert.equal(receivers[0].disconnected, true);
assert.equal(nodes.connect.disabled, false);
assert.equal(nodes.disconnect.disabled, true);
assert.equal(nodes.host.disabled, false);
assert.equal(nodes.code.disabled, false);
assert.equal(nodes.subtitle.hidden, true);
assert.equal(nodes["empty-subtitle"].hidden, false);
assert.equal(nodes.game.textContent, "PS Link");
assert.equal(nodes.source.textContent, "");
assert.equal(nodes.translation.textContent, "");
assert.equal(nodes.captions.textContent, "");
assert.equal(nodes["media-notice"].hidden, true);
assert.ok(playerStops >= 1);
assert.ok(captionClears >= 1);

// A new session can start after explicit disconnect.
provideCredentials();
submit();

assert.equal(receivers.length, 2);
assert.equal(receivers[1].connected, true);

// Events from the previous session must not modify the new session UI.
receivers[0].options.onSubtitle({
  game: "Stale Game",
  source: "Stale",
  translation: "Stale"
});

assert.equal(nodes.game.textContent, "PS Link");
assert.equal(nodes.translation.textContent, "");

// Current-session callbacks must still work.
receivers[1].options.onSubtitle({
  game: "Current Game",
  source: "Current source",
  translation: "Current translation"
});

assert.equal(nodes.game.textContent, "Current Game");
assert.equal(nodes.translation.textContent, "Current translation");

// Escape disconnects the active session.
windowListeners.keydown({ key: "Escape", keyCode: 27 });

assert.equal(receivers[1].disconnected, true);
assert.equal(nodes.connect.disabled, false);
assert.equal(nodes.disconnect.disabled, true);
assert.equal(nodes.subtitle.hidden, true);
assert.equal(nodes.translation.textContent, "");

// Cleanup must continue when independent components throw.
provideCredentials();
submit();

assert.equal(receivers.length, 3);

const cleanupReceiver = receivers[2];
cleanupReceiver.failDisconnect = true;

failPlayerStop = true;
failCaptionClear = true;

const previousPlayerStops = playerStops;
const previousCaptionClears = captionClears;

nodes.disconnect.fire("click");

assert.equal(cleanupReceiver.disconnectCalls, 1);
assert.equal(playerStops, previousPlayerStops + 1);
assert.equal(captionClears, previousCaptionClears + 1);

assert.equal(nodes.connect.disabled, false);
assert.equal(nodes.disconnect.disabled, true);
assert.equal(nodes.host.disabled, false);
assert.equal(nodes.code.disabled, false);
assert.equal(nodes.game.textContent, "PS Link");
assert.equal(nodes.source.textContent, "");
assert.equal(nodes.translation.textContent, "");
assert.equal(nodes.captions.textContent, "");
assert.equal(nodes["media-notice"].hidden, true);

failPlayerStop = false;
failCaptionClear = false;

// Authentication rejection must release the UI and invalidate callbacks.
provideCredentials();
submit();

assert.equal(receivers.length, 4);

const rejectedReceiver = receivers[3];
rejectedReceiver.authRejected = true;

rejectedReceiver.options.onError({
  message: "رفض رمز الاقتران"
});

assert.equal(rejectedReceiver.disconnected, true);
assert.equal(nodes.connect.disabled, false);
assert.equal(nodes.disconnect.disabled, true);
assert.equal(nodes.host.disabled, false);
assert.equal(nodes.code.disabled, false);
assert.equal(nodes.status.textContent, "رفض رمز الاقتران");
assert.equal(nodes.status.attributes["data-state"], "error");

rejectedReceiver.options.onSubtitle({
  game: "Rejected stale event",
  source: "stale",
  translation: "stale"
});

assert.equal(nodes.game.textContent, "PS Link");
assert.equal(nodes.translation.textContent, "");

console.log("PS Link Web app lifecycle tests passed");

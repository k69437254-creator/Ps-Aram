/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const app = fs.readFileSync(
  path.join(__dirname, "../app.js"), "utf8"
);

const elements = new Map();
const windowListeners = {};
const initialState = { screen: "home" };

function element(id) {
  if (!elements.has(id)) {
    elements.set(id, {
      id,
      value: id === "host" ? "192.168.1.20" : "123456",
      disabled: false,
      hidden: false,
      textContent: "",
      listeners: {},
      addEventListener(name, callback) {
        this.listeners[name] = callback;
      },
      setAttribute() {},
      classList: {
        add() {},
        remove() {}
      }
    });
  }
  return elements.get(id);
}

const document = {
  body: {
    classList: {
      add() {},
      remove() {}
    }
  },
  getElementById: element
};

let pushCount = 0;
let replaceCount = 0;
let disconnectCount = 0;
let lastOptions = null;

const history = {
  state: initialState,
  pushState(state) {
    pushCount += 1;
    this.state = state;
  },
  replaceState(state) {
    replaceCount += 1;
    this.state = state;
  }
};

const window = {
  location: { href: "file:///index.html" },
  history,
  addEventListener(name, callback) {
    windowListeners[name] = callback;
  },
  PSLinkMediaPlayer: function () {
    return {
      start() {},
      stop() {}
    };
  },
  PSLinkReceiver: function (options) {
    lastOptions = options;
    this.connect = function () {};
    this.disconnect = function () {
      disconnectCount += 1;
    };
  }
};

vm.runInNewContext(app, { window, document });

function connect() {
  element("pairing-form").listeners.submit({
    preventDefault() {}
  });
  assert.equal(typeof lastOptions.onReady, "function");
  return lastOptions;
}

function manualDisconnect() {
  element("disconnect").listeners.click();
}

// A successful connection arms exactly one Back history entry.
const first = connect();
first.onReady(true);
first.onReady(true);

assert.equal(pushCount, 1);
assert.equal(history.state.psLinkBackGuard, true);

// Remote Back consumes the guard and disconnects exactly once.
history.state = initialState;
windowListeners.popstate({ state: initialState });

assert.equal(disconnectCount, 1);
assert.equal(element("disconnect").disabled, true);
assert.equal(replaceCount, 0);

windowListeners.popstate({ state: initialState });
assert.equal(disconnectCount, 1);

// Manual disconnect removes the guard without navigating backward.
const second = connect();
second.onReady(true);

assert.equal(pushCount, 2);
manualDisconnect();

assert.equal(disconnectCount, 2);
assert.equal(replaceCount, 1);
assert.equal(history.state.screen, "home");

windowListeners.popstate({ state: initialState });
assert.equal(disconnectCount, 2);

// A network loss releases Back history but preserves the receiver.
// The same receiver must be allowed to report successful reconnection.
const third = connect();
third.onReady(true);

assert.equal(pushCount, 3);
third.onReady(false);

assert.equal(replaceCount, 2);
assert.equal(element("connect").disabled, false);

third.onReady(true);

assert.equal(
  pushCount,
  4,
  "automatic reconnection must be allowed to re-arm Back history"
);
assert.equal(history.state.psLinkBackGuard, true);

// Manual disconnect invalidates callbacks from that old connection.
manualDisconnect();

assert.equal(disconnectCount, 3);
assert.equal(replaceCount, 3);

third.onReady(true);
assert.equal(
  pushCount,
  4,
  "callbacks after manual disconnect must be ignored"
);

// A new receiver can connect normally after manual disconnect.
const fourth = connect();
fourth.onReady(true);

assert.equal(pushCount, 5);

// Escape remains a supported manual disconnect shortcut.
let prevented = false;
windowListeners.keydown({
  key: "Escape",
  keyCode: 27,
  preventDefault() {
    prevented = true;
  }
});

assert.equal(prevented, true);
assert.equal(disconnectCount, 4);
assert.equal(replaceCount, 4);

// The old receiver must not affect the newer session.
fourth.onReady(true);
assert.equal(pushCount, 5);

console.log("webOS Back history behavioral tests passed.");
console.log("webOS manual disconnect history tests passed.");
console.log("webOS automatic reconnection lifecycle tests passed.");
console.log("webOS stale connection callback tests passed.");

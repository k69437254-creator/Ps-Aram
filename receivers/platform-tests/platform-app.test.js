/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";

var assert = require("assert");
var fs = require("fs");
var path = require("path");
var vm = require("vm");
var root = path.resolve(__dirname, "../..");

function loadClient(relativePath, pageLocation) {
  var sockets = [];
  var timers = [];
  var now = 1000000;

  function FakeWebSocket(url) {
    this.url = url;
    this.readyState = 0;
    this.binaryType = "";
    this.sent = [];
    sockets.push(this);
  }

  FakeWebSocket.prototype.send = function (value) {
    this.sent.push(value);
  };
  FakeWebSocket.prototype.open = function () {
    this.readyState = 1;
    if (this.onopen) this.onopen();
  };
  FakeWebSocket.prototype.message = function (data) {
    if (this.onmessage) this.onmessage({ data: data });
  };
  FakeWebSocket.prototype.close = function (code, reason) {
    this.readyState = 3;
    this.closeCode = code;
    this.closeReason = reason;
    if (this.onclose) this.onclose({ code: code, reason: reason });
  };

  var context = {
    module: { exports: {} },
    exports: {},
    WebSocket: FakeWebSocket,
    URL: URL,
    Date: { now: function () { return now; } },
    location: pageLocation,
    setTimeout: function (callback, delay) {
      var timer = { callback: callback, delay: delay, cancelled: false };
      timers.push(timer);
      return timer;
    },
    clearTimeout: function (timer) {
      if (timer) timer.cancelled = true;
    }
  };

  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(path.join(root, "receivers", relativePath), "utf8"),
    context,
    { filename: relativePath }
  );

  return {
    Receiver: context.module.exports,
    context: context,
    sockets: sockets,
    timers: timers,
    advanceTime: function (milliseconds) { now += milliseconds; }
  };
}

function requireMethod(object, method, label) {
  assert.strictEqual(typeof object[method], "function", label + " missing " + method);
}

function verifyTransportBehavior(name) {
  var harness = loadClient(name + "/pslink-client.js", {
    protocol: "http:",
    origin: "http://tv.local"
  });
  var Receiver = harness.Receiver;
  var shared = loadClient("web/pslink-client.js", {
    protocol: "http:",
    origin: "http://tv.local"
  }).Receiver;

  assert.strictEqual(Receiver.PROTOCOL_VERSION, shared.PROTOCOL_VERSION,
    name + " protocol version differs from shared transport");
  ["parseMediaPacket", "normalizePairingCode"].forEach(function (method) {
    requireMethod(Receiver, method, name);
    requireMethod(shared, method, "shared transport");
    requireMethod(Receiver.prototype, method === "parseMediaPacket" ? "connect" : "disconnect", name);
  });
  ["connect", "disconnect", "configureMedia", "getConnectionState", "isReady"]
    .forEach(function (method) {
    requireMethod(Receiver.prototype, method, name);
    requireMethod(shared.prototype, method, "shared transport");
  });

  assert.strictEqual(Receiver.normalizePairingCode("١٢٣٤٥٦"), "123456");
  assert.strictEqual(Receiver.normalizePairingCode("۱۲۳۴۵۶"), "123456");
  assert.strictEqual(Receiver.normalizePairingCode("12345"), null);
  assert.strictEqual(Receiver.normalizePairingCode("12x456"), null);

  if (typeof Receiver.resolveSocketUrl === "function") {
    assert.strictEqual(
      Receiver.resolveSocketUrl("192.168.1.5", { protocol: "http:" }),
      "ws://192.168.1.5:8765/subtitle"
    );
    assert.strictEqual(
      Receiver.resolveSocketUrl("tv.example", { protocol: "https:" }),
      "wss://tv.example/subtitle"
    );
    assert.throws(function () {
      Receiver.resolveSocketUrl("ws://tv.example:8765/subtitle", { protocol: "https:" });
    }, /WSS|آمن/);
    assert.throws(function () {
      Receiver.resolveSocketUrl("wss://tv.example/other", { protocol: "https:" });
    });
    assert.throws(function () {
      Receiver.resolveSocketUrl("tv.example/path", { protocol: "http:" });
    });
    assert.strictEqual(
      Receiver.normalizeStreamUrl("http://phone.local/hls/index.m3u8", {
        protocol: "https:", origin: "https://receiver.example"
      }),
      "https://receiver.example/hls/index.m3u8"
    );
  } else {
    assert.strictEqual(
      Receiver.normalizeStreamUrl("http://phone.local/hls/index.m3u8", {
        protocol: "https:", origin: "https://receiver.example"
      }),
      null
    );
  }
  assert.strictEqual(
    Receiver.normalizeStreamUrl("http://phone.local/private/file.m3u8", {
      protocol: "https:", origin: "https://receiver.example"
    }),
    null
  );
  assert.strictEqual(
    Receiver.normalizeStreamUrl("javascript:alert(1)", { protocol: "http:" }),
    null
  );

  var observed = { ready: [], subtitles: [], streams: [], media: [], errors: [] };
  var receiver = new Receiver({
    onReady: function (value) { observed.ready.push(value); },
    onSubtitle: function (value) { observed.subtitles.push(value); },
    onStreamUrl: function (value) { observed.streams.push(value); },
    onMediaPacket: function (value) { observed.media.push(value); },
    onError: function (value) { observed.errors.push(value); }
  });

  receiver.connect("192.168.1.5", "١٢٣٤٥٦");
  assert.strictEqual(harness.sockets.length, 1);
  var socket = harness.sockets[0];
  assert.strictEqual(socket.url, "ws://192.168.1.5:8765/subtitle");
  assert.strictEqual(receiver.getConnectionState(), "connecting");
  assert.strictEqual(receiver.isReady(), false);
  receiver.configureMedia(1920, 1080, 0, 30);
  socket.open();
  assert.strictEqual(receiver.getConnectionState(), "authenticating");

  var auth = JSON.parse(socket.sent[0]);
  assert.strictEqual(auth.v, Receiver.PROTOCOL_VERSION);
  assert.strictEqual(auth.kind, "auth");
  assert.strictEqual(auth.code, "123456");
  assert.strictEqual(auth.sessionId, receiver.sessionId);

  socket.message(JSON.stringify({
    v: Receiver.PROTOCOL_VERSION, kind: "ready", sessionId: "stale-session"
  }));
  assert.strictEqual(receiver.isReady(), false,
    "ready from a different session must be ignored");
  socket.message(JSON.stringify({
    v: Receiver.PROTOCOL_VERSION, kind: "ready",
    sessionId: receiver.sessionId
  }));
  assert.strictEqual(observed.ready[observed.ready.length - 1], true);
  assert.strictEqual(receiver.getConnectionState(), "ready");
  assert.strictEqual(receiver.isReady(), true);
  var heartbeatTimer = harness.timers.find(function (timer) {
    return !timer.cancelled && timer.delay === 15000;
  });
  assert(heartbeatTimer, "ready connection must schedule a heartbeat");
  heartbeatTimer.callback();
  assert(socket.sent.some(function (item) {
    return typeof item === "string" && JSON.parse(item).kind === "heartbeat";
  }), "heartbeat must be sent over the active socket");
  assert(socket.sent.some(function (item) {
    var message = typeof item === "string" ? JSON.parse(item) : {};
    return message.kind === "heartbeat" && message.sessionId === receiver.sessionId;
  }), "heartbeat must carry the active session ID");
  socket.message(JSON.stringify({
    v: Receiver.PROTOCOL_VERSION,
    kind: "heartbeat_ack",
    sessionId: receiver.sessionId
  }));
  assert.strictEqual(receiver.isReady(), true);
  assert(socket.sent.some(function (item) {
    return typeof item === "string" && JSON.parse(item).kind === "media_config";
  }), "media configuration must be replayed after authentication");

  socket.message(JSON.stringify({
    v: Receiver.PROTOCOL_VERSION,
    kind: "subtitle",
    sessionId: receiver.sessionId,
    id: "caption-1",
    source: "Hello",
    translation: "مرحباً"
  }));
  assert.strictEqual(observed.subtitles.length, 1);
  assert.strictEqual(observed.subtitles[0].translation, "مرحباً");
  assert(socket.sent.some(function (item) {
    if (typeof item !== "string") return false;
    var message = JSON.parse(item);
    return message.kind === "ack" && message.sessionId === receiver.sessionId;
  }), "subtitle receipt must be acknowledged");

  socket.message(JSON.stringify({
    v: Receiver.PROTOCOL_VERSION,
    kind: "stream_url",
    sessionId: "stale-session",
    url: "http://192.168.1.5/hls/stale.m3u8"
  }));
  assert.strictEqual(observed.streams.length, 0,
    "stream URLs from a different session must be ignored");
  socket.message(JSON.stringify({
    v: Receiver.PROTOCOL_VERSION,
    kind: "stream_url",
    sessionId: receiver.sessionId,
    url: "http://192.168.1.5/hls/index.m3u8"
  }));
  assert.strictEqual(observed.streams.length, 1);
  assert.strictEqual(observed.streams[0].url, "http://192.168.1.5/hls/index.m3u8");

  var mediaBuffer = vm.runInContext(
    "(function(){var b=new ArrayBuffer(32);var d=new DataView(b);" +
    "d.setUint32(0,0x50534C4D,false);d.setUint8(4,1);d.setUint8(5,1);" +
    "d.setUint16(6,1,false);d.setUint32(8,0,false);d.setUint32(12,1000000000,false);" +
    "d.setInt32(16,0,false);d.setInt32(20,0,false);d.setUint32(24,4,false);" +
    "new Uint8Array(b,28).set([1,2,3,4]);return b;})()",
    harness.context
  );
  socket.message(mediaBuffer);
  assert.strictEqual(observed.media.length, 1);
  assert.strictEqual(observed.media[0].type, "video");
  assert.strictEqual(observed.media[0].keyFrame, true);

  socket.close(1006, "network lost");
  assert.strictEqual(receiver.getConnectionState(), "disconnected");
  var reconnectTimer = harness.timers.find(function (timer) {
    return !timer.cancelled && timer.delay === 1000;
  });
  assert(reconnectTimer, "unexpected close must schedule bounded reconnect");
  receiver.disconnect();
  assert.strictEqual(reconnectTimer.cancelled, true,
    "manual disconnect must cancel a pending reconnect");
  assert.strictEqual(receiver.getConnectionState(), "disconnected");
  assert.strictEqual(receiver.closedByUser, true);
}

function verifyHeartbeatTimeout(name) {
  var harness = loadClient(name + "/pslink-client.js", {
    protocol: "http:",
    origin: "http://tv.local"
  });
  var receiver = new harness.Receiver();
  receiver.connect("192.168.1.5", "123456");
  var socket = harness.sockets[0];
  socket.open();
  socket.message(JSON.stringify({
    v: receiver.constructor.PROTOCOL_VERSION,
    kind: "ready",
    sessionId: receiver.sessionId
  }));

  var heartbeatTimer = harness.timers.find(function (timer) {
    return !timer.cancelled && timer.delay === 15000;
  });
  assert(heartbeatTimer, name + " must start a heartbeat after readiness");
  harness.advanceTime(45000);
  heartbeatTimer.callback();
  assert.strictEqual(socket.closeCode, 4000, name + " must close a stale heartbeat");
  assert.strictEqual(receiver.getConnectionState(), "disconnected");

  var reconnectTimer = harness.timers.find(function (timer) {
    return !timer.cancelled && timer.delay === 1000;
  });
  assert(reconnectTimer, name + " must reconnect after heartbeat timeout");
  receiver.disconnect();
  assert.strictEqual(reconnectTimer.cancelled, true);
}

["tizen", "webos"].forEach(function (name) {
  var dir = path.join(root, "receivers", name);
  ["index.html", "app.css", "app.js", "pslink-client.js", "media-player.js", "README.md"]
    .forEach(function (file) {
      assert(fs.existsSync(path.join(dir, file)), name + " missing " + file);
    });

  var html = fs.readFileSync(path.join(dir, "index.html"), "utf8");
  var app = fs.readFileSync(path.join(dir, "app.js"), "utf8");
  var player = fs.readFileSync(path.join(dir, "media-player.js"), "utf8");
  ["pairing-form", "host", "code", "status", "translation", "captions"]
    .forEach(function (id) {
      assert(html.indexOf('id="' + id + '"') !== -1, name + " missing UI element " + id);
    });
  ["PSLinkReceiver", "onStatus", "onReady", "onError", "onSubtitle", "onCaptions",
    "onMediaPacket", "onStreamUrl", "mediaPlayer.start(stream.url)",
    "receiver.connect(host, code)", "disconnect"]
    .forEach(function (token) {
      assert(app.indexOf(token) !== -1, name + " missing adapter behavior " + token);
    });

  assert(player.indexOf("PSLinkMediaPlayer") !== -1, name + " media adapter missing");
  if (name === "tizen") assert(player.indexOf("prepareAsync") !== -1, "Tizen AVPlay adapter missing prepareAsync");
  if (name === "webos") assert(player.indexOf("game-video") !== -1, "webOS HTML media element missing");
  assert(html.indexOf('src="pslink-client.js"') !== -1, name + " does not load packaged transport client");
  assert(app.indexOf("if (!streamUrlReceived)") !== -1, name + " HLS notification guard missing");
  assert(app.indexOf("streamUrlReceived = true;") !== -1, name + " HLS received state missing");
  verifyTransportBehavior(name);
});

["web", "xbox-edge"].forEach(verifyTransportBehavior);
["web", "tizen", "webos", "xbox-edge"].forEach(verifyHeartbeatTimeout);

var tizenManifest = fs.readFileSync(path.join(root, "receivers", "tizen", "config.xml"), "utf8");
var webosManifest = JSON.parse(fs.readFileSync(path.join(root, "receivers", "webos", "appinfo.json"), "utf8"));
assert(tizenManifest.indexOf("tv-samsung") !== -1, "Tizen target metadata missing");
assert(tizenManifest.indexOf("index.html") !== -1, "Tizen entry point missing");
assert(tizenManifest.indexOf("privilege/avplay") !== -1, "Tizen AVPlay privilege missing");
assert.strictEqual(webosManifest.type, "web");
assert.strictEqual(webosManifest.main, "index.html");
assert(webosManifest.id, "webOS application id missing");

console.log("PS Link Tizen/webOS protocol, security URL, callback, and HLS adapter tests passed.");
console.log("Note: these automated tests do not build vendor packages or validate physical media decoding.");

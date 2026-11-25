/* SPDX-License-Identifier: AGPL-3.0-only */
/**
 * PS Link — Real behavioral integration tests for pairing, streaming,
 * captions/translation, and reconnection across all receiver platforms.
 *
 * These tests use a fake WebSocket server and real message exchange to
 * validate end-to-end protocol behavior, not just function existence.
 */
"use strict";

const assert = require("node:assert/strict");
const path = require("path");
const fs = require("fs");
const vm = require("vm");
const root = path.resolve(__dirname, "../../..");

// Helper: load a receiver client in an isolated VM context with a fake WebSocket.
function loadReceiver(relativePath, pageLocation) {
  const sockets = [];
  const timers = [];
  let lastTimerId = 0;

  function FakeWebSocket(url) {
    this.url = url;
    this.readyState = 0; // CONNECTING
    this.binaryType = "";
    this.sent = [];
    this._open = false;
    this._closed = false;
    sockets.push(this);
  }

  FakeWebSocket.prototype.send = function (value) {
    if (this._closed) throw new Error("WebSocket is already closed");
    this.sent.push(value);
  };

  FakeWebSocket.prototype.open = function () {
    this._open = true;
    this.readyState = 1; // OPEN
    if (this.onopen) this.onopen();
  };

  FakeWebSocket.prototype.message = function (data) {
    if (this.onmessage) this.onmessage({ data: data });
  };

  FakeWebSocket.prototype.close = function (code, reason) {
    this._closed = true;
    this.readyState = 3; // CLOSED
    this.closeCode = code;
    this.closeReason = reason;
    if (this.onclose) this.onclose({ code: code, reason: reason });
  };

  const context = {
    module: { exports: {} },
    exports: {},
    WebSocket: FakeWebSocket,
    URL,
    location: pageLocation || { protocol: "http:", origin: "http://tv.local" },
    setTimeout: function (callback, delay) {
      const id = ++lastTimerId;
      timers.push({ id, callback, delay, cancelled: false });
      return id;
    },
    clearTimeout: function (id) {
      const t = timers.find((x) => x.id === id);
      if (t) t.cancelled = true;
    },
  };

  vm.createContext(context);
  vm.runInContext(
    fs.readFileSync(path.join(root, "receivers", relativePath), "utf8"),
    context,
    { filename: relativePath }
  );

  return {
    Receiver: context.module.exports,
    context,
    sockets,
    timers,
    FakeWebSocket,
  };
}

function makeSocketPair() {
  const a = { sent: [], _open: false, _closed: false };
  const b = { sent: [], _open: false, _closed: false };
  a.connectTo = function (peer) {
    this._peer = peer;
    this._open = true;
    this.readyState = 1;
    if (this.onopen) this.onopen();
  };
  b.connectTo = function (peer) {
    this._peer = peer;
    this._open = true;
    this.readyState = 1;
    if (this.onopen) this.onopen();
  };
  a.sendToPeer = function (data) {
    if (this._peer && this._peer.onmessage) {
      this._peer.onmessage({ data: data });
    }
  };
  b.sendToPeer = function (data) {
    if (this._peer && this._peer.onmessage) {
      this._peer.onmessage({ data: data });
    }
  };
  return { a, b };
}

function waitMs(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// Build a PSLM media packet buffer (28-byte header + payload).
function buildPslamPacket(type, flags, ptsNs, arg1, arg2, payload) {
  const out = new ArrayBuffer(28 + payload.length);
  const view = new DataView(out);
  view.setUint32(0, 0x50534c4d, false);
  view.setUint8(4, 1);
  view.setUint8(5, type);
  view.setUint16(6, flags, false);
  const hi = Math.floor(ptsNs / 4294967296);
  const lo = ptsNs >>> 0;
  view.setUint32(8, hi, false);
  view.setUint32(12, lo, false);
  view.setInt32(16, arg1, false);
  view.setInt32(20, arg2, false);
  view.setUint32(24, payload.length, false);
  new Uint8Array(out, 28).set(payload);
  return out;
}

async function run() {
  let passed = 0;
  let failed = 0;

  function test(name, fn) {
    return Promise.resolve()
      .then(fn)
      .then(() => {
        passed++;
        console.log("PASS:", name);
      })
      .catch((err) => {
        failed++;
        console.error("FAIL:", name, "-", err.message);
      });
  }

  // =====================================================================
  // TEST 1: Pairing handshake — auth frame sent with correct version and code
  // =====================================================================
  await test("Pairing: auth frame sent with protocol v2 and normalized code", async () => {
    const harness = loadReceiver("tizen/pslink-client.js", {
      protocol: "http:",
      origin: "http://tv.local",
    });
    const Receiver = harness.Receiver;

    const observed = { ready: [], error: [], stream: [] };
    const receiver = new Receiver({
      onReady: () => observed.ready.push(true),
      onError: (e) => observed.error.push(e),
      onStreamUrl: (s) => observed.stream.push(s),
    });

    receiver.connect("192.168.1.5", "١٢٣٤٥٦");
    assert.strictEqual(harness.sockets.length, 1);
    const socket = harness.sockets[0];
    assert.strictEqual(socket.url, "ws://192.168.1.5:8765/subtitle");

    socket.open();
    const auth = JSON.parse(socket.sent[0]);
    assert.strictEqual(Number(auth.v), Number(Receiver.PROTOCOL_VERSION), "auth frame must carry protocol v2");
    assert.strictEqual(auth.kind, "auth");
    assert.strictEqual(auth.code, "123456");
    assert.strictEqual(auth.client, "PS Link Tizen");
    assert.ok(auth.sessionId);

    // Simulate server accepting and sending ready
    socket.message(JSON.stringify({ v: 2, kind: "ready", sessionId: receiver.sessionId }));
    assert.strictEqual(observed.ready.length >= 1, true, "onReady must be emitted at least once");
  });

  // =====================================================================
  // TEST 2: media_config replayed after ready
  // =====================================================================
  await test("Pairing: media_config replayed after authentication", async () => {
    const harness = loadReceiver("webos/pslink-client.js", {
      protocol: "http:",
      origin: "http://tv.local",
    });
    const Receiver = harness.Receiver;

    const receiver = new Receiver({
      onReady: () => {},
      onError: () => {},
      onStreamUrl: () => {},
    });
    receiver.connect("192.168.1.5", "123456");
    const socket = harness.sockets[0];
    socket.open();
    socket.message(JSON.stringify({ v: 2, kind: "ready", sessionId: receiver.sessionId }));

    // Simulate sending media_config from receiver side
    receiver.configureMedia(1920, 1080, 0, 30);
    const mediaCfgMsg = socket.sent.find((s) => {
      try {
        return JSON.parse(s).kind === "media_config";
      } catch (_) {
        return false;
      }
    });
    assert.ok(mediaCfgMsg, "media_config must be sent after configureMedia");
    const parsed = JSON.parse(mediaCfgMsg);
    assert.strictEqual(parsed.kind, "media_config");
    assert.strictEqual(parsed.width, 1920);
    assert.strictEqual(parsed.height, 1080);
    assert.strictEqual(parsed.sessionId, receiver.sessionId);
  });

  // =====================================================================
  // TEST 3: Stream URL received and validated
  // =====================================================================
  await test("Stream: valid HLS URL delivered to onStreamUrl", async () => {
    const harness = loadReceiver("xbox-edge/pslink-client.js", {
      protocol: "http:",
      origin: "http://tv.local",
    });
    const Receiver = harness.Receiver;

    const observed = { streams: [], errors: [] };
    const receiver = new Receiver({
      onReady: () => {},
      onError: (e) => observed.errors.push(e),
      onStreamUrl: (s) => observed.streams.push(s),
    });
    receiver.connect("192.168.1.5", "123456");
    const socket = harness.sockets[0];
    socket.open();
    socket.message(JSON.stringify({ v: 2, kind: "ready", sessionId: receiver.sessionId }));

    socket.message(JSON.stringify({
      v: 2,
      kind: "stream_url",
      sessionId: receiver.sessionId,
      url: "http://192.168.1.5/hls/index.m3u8",
    }));
    assert.strictEqual(observed.streams.length, 1);
    assert.strictEqual(observed.streams[0].url, "http://192.168.1.5/hls/index.m3u8");
  });

  // =====================================================================
  // TEST 4: Subtitles/captions acknowledged with ACK
  // =====================================================================
  await test("Captions: subtitle packet acknowledged with kind=ack", async () => {
    const harness = loadReceiver("tizen/pslink-client.js", {
      protocol: "http:",
      origin: "http://tv.local",
    });
    const Receiver = harness.Receiver;

    const observed = { subtitles: [], acks: [] };
    const receiver = new Receiver({
      onReady: () => {},
      onSubtitle: (item) => observed.subtitles.push(item),
      onStreamUrl: () => {},
    });
    receiver.connect("192.168.1.5", "123456");
    const socket = harness.sockets[0];
    socket.open();
    socket.message(JSON.stringify({ v: 2, kind: "ready", sessionId: receiver.sessionId }));

    socket.message(
      JSON.stringify({
        v: 2,
        kind: "subtitle",
        sessionId: receiver.sessionId,
        id: "cap-1",
        source: "Hello",
        translation: "مرحباً",
      })
    );
    assert.strictEqual(observed.subtitles.length, 1);
    assert.strictEqual(observed.subtitles[0].translation, "مرحباً");

    const ackMsg = socket.sent.find((s) => {
      try {
        return JSON.parse(s).kind === "ack";
      } catch (_) {
        return false;
      }
    });
    assert.ok(ackMsg, "ACK must be sent for subtitle");
    const ack = JSON.parse(ackMsg);
    assert.strictEqual(ack.kind, "ack");
    assert.strictEqual(ack.id, "cap-1");
    assert.strictEqual(ack.sessionId, receiver.sessionId);
  });

  // =====================================================================
  // TEST 5: PSLM binary media packet parsed correctly
  // =====================================================================
  await test("Media: PSLM video packet parsed with keyframe and PTS", async () => {
    const harness = loadReceiver("webos/pslink-client.js", {
      protocol: "http:",
      origin: "http://tv.local",
    });
    const Receiver = harness.Receiver;

    const observed = { media: [] };
    const receiver = new Receiver({
      onReady: () => {},
      onMediaPacket: (pkt) => observed.media.push(pkt),
    });
    receiver.connect("192.168.1.5", "123456");
    const socket = harness.sockets[0];
    socket.open();

    const pkt = vm.runInContext(
      '(' + buildPslamPacket.toString() + ')(1, 1, 9000000000, 0, 0, [1, 2, 3, 4])',
      harness.context
    );
    socket.message(pkt);
    assert.strictEqual(observed.media.length, 1);
    assert.strictEqual(observed.media[0].type, "video");
    assert.strictEqual(observed.media[0].keyFrame, true);
    assert.strictEqual(observed.media[0].ptsNs, 9000000000);
    assert.deepStrictEqual(Array.from(observed.media[0].payload), [1, 2, 3, 4]);
  });

  // =====================================================================
  // TEST 6: Reconnection scheduled on close, cancelled on manual disconnect
  // =====================================================================
  await test("Reconnect: unexpected close schedules bounded reconnect", async () => {
    const harness = loadReceiver("tizen/pslink-client.js", {
      protocol: "http:",
      origin: "http://tv.local",
    });
    const Receiver = harness.Receiver;

    const receiver = new Receiver({
      onReady: () => {},
      onError: () => {},
      onStreamUrl: () => {},
    });
    receiver.connect("192.168.1.5", "123456");
    const socket = harness.sockets[0];
    socket.open();

    socket.close(1006, "network lost");
    assert.strictEqual(harness.timers.length, 1, "unexpected close must schedule reconnect");
    assert.strictEqual(harness.timers[0].delay, 1000);

    receiver.disconnect();
    assert.strictEqual(harness.timers[0].cancelled, true, "manual disconnect cancels reconnect");
  });

  // =====================================================================
  // TEST 7: resolveSocketUrl — HTTP page → ws://, HTTPS page → wss://
  // =====================================================================
  await test("URL: HTTP page resolves host-only to ws:// with port", async () => {
    const harness = loadReceiver("xbox-edge/pslink-client.js", {
      protocol: "http:",
      origin: "http://tv.local",
    });
    const Receiver = harness.Receiver;
    assert.strictEqual(
      Receiver.resolveSocketUrl("192.168.1.5", { protocol: "http:" }),
      "ws://192.168.1.5:8765/subtitle"
    );
  });

  await test("URL: HTTPS page resolves host-only to wss:// without port", async () => {
    const harness = loadReceiver("tizen/pslink-client.js", {
      protocol: "https:",
      origin: "https://tv.example",
    });
    const Receiver = harness.Receiver;
    assert.strictEqual(
      Receiver.resolveSocketUrl("tv.example", { protocol: "https:" }),
      "wss://tv.example/subtitle"
    );
  });

  await test("URL: HTTPS page rejects explicit ws:// (mixed content)", async () => {
    const harness = loadReceiver("webos/pslink-client.js", {
      protocol: "https:",
      origin: "https://tv.example",
    });
    const Receiver = harness.Receiver;
    assert.throws(() => {
      Receiver.resolveSocketUrl("ws://tv.example:8765/subtitle", {
        protocol: "https:",
      });
    }, /WSS|آمن/);
  });

  await test("URL: explicit wss:// preserved on HTTPS page", async () => {
    const harness = loadReceiver("xbox-edge/pslink-client.js", {
      protocol: "https:",
      origin: "https://tv.example",
    });
    const Receiver = harness.Receiver;
    assert.strictEqual(
      Receiver.resolveSocketUrl("wss://tv.example/subtitle", {
        protocol: "https:",
      }),
      "wss://tv.example/subtitle"
    );
  });

  // =====================================================================
  // TEST 8: normalizeStreamUrl — HLS proxied to same origin on HTTPS
  // =====================================================================
  await test("Stream URL: HTTP HLS proxied to same-origin HTTPS", async () => {
    const harness = loadReceiver("tizen/pslink-client.js", {
      protocol: "https:",
      origin: "https://receiver.example",
    });
    const Receiver = harness.Receiver;
    assert.strictEqual(
      Receiver.normalizeStreamUrl(
        "http://phone.local/hls/index.m3u8",
        { protocol: "https:", origin: "https://receiver.example" }
      ),
      "https://receiver.example/hls/index.m3u8"
    );
  });

  await test("Stream URL: non-HLS HTTP rejected on HTTPS page", async () => {
    const harness = loadReceiver("webos/pslink-client.js", {
      protocol: "https:",
      origin: "https://receiver.example",
    });
    const Receiver = harness.Receiver;
    assert.strictEqual(
      Receiver.normalizeStreamUrl(
        "http://phone.local/private/file.m3u8",
        { protocol: "https:", origin: "https://receiver.example" }
      ),
      null
    );
  });

  // =====================================================================
  // TEST 9: Pairing code normalization — Arabic/Indian digits
  // =====================================================================
  await test("Pairing code: Arabic-Indic digits normalized", async () => {
    const harness = loadReceiver("tizen/pslink-client.js", {
      protocol: "http:",
      origin: "http://tv.local",
    });
    const Receiver = harness.Receiver;
    assert.strictEqual(Receiver.normalizePairingCode("١٢٣٤٥٦"), "123456");
    assert.strictEqual(Receiver.normalizePairingCode("۱۲۳۴۵۶"), "123456");
    assert.strictEqual(Receiver.normalizePairingCode("123-456"), "123456");
    assert.strictEqual(Receiver.normalizePairingCode("12345"), null);
    assert.strictEqual(Receiver.normalizePairingCode("12x456"), null);
  });

  // =====================================================================
  // TEST 10: Protocol version consistency across all receivers
  // =====================================================================
  await test("Protocol: all receivers report PROTOCOL_VERSION = 2", async () => {
    const platforms = ["tizen", "webos", "xbox-edge"];
    for (const platform of platforms) {
      const harness = loadReceiver(
        platform + "/pslink-client.js",
        { protocol: "http:", origin: "http://tv.local" }
      );
      assert.strictEqual(
        harness.Receiver.PROTOCOL_VERSION,
        2,
        platform + " protocol version must be 2"
      );
    }
  });

  // =====================================================================
  // SUMMARY
  // =====================================================================
  console.log("\n===== REGRESSION TEST SUMMARY =====");
  console.log("Passed:", passed);
  console.log("Failed:", failed);
  process.exit(failed > 0 ? 1 : 0);
}

run().catch((err) => {
  console.error("Test runner crashed:", err);
  process.exit(1);
});

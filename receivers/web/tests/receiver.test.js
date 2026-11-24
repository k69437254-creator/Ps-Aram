/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";

const assert = require("node:assert/strict");
const Receiver = require("../pslink-client.js");

// ---- normalizePairingCode ----
assert.equal(Receiver.normalizePairingCode("١٢٣-٤٥٦"), "123456");
assert.equal(Receiver.normalizePairingCode("۱۲۳۴۵۶"), "123456");
assert.equal(Receiver.normalizePairingCode("12a456"), null);
assert.equal(Receiver.normalizePairingCode("12345"), null);
assert.equal(Receiver.normalizePairingCode(null), null);
assert.equal(Receiver.normalizePairingCode(""), null);

// ---- normalizeStreamUrl: 4 cases ----
const httpPage = { protocol: "http:", origin: "http://receiver.local" };
const httpsPage = { protocol: "https:", origin: "https://receiver.local" };

// HTTP page + HTTP stream -> accept
assert.equal(
  Receiver.normalizeStreamUrl("http://phone.local/hls/index.m3u8", httpPage),
  "http://phone.local/hls/index.m3u8"
);
// HTTP page + HTTPS stream -> accept
assert.equal(
  Receiver.normalizeStreamUrl("https://phone.local/hls/index.m3u8", httpPage),
  "https://phone.local/hls/index.m3u8"
);
// HTTPS page + HTTPS stream -> accept
assert.equal(
  Receiver.normalizeStreamUrl("https://phone.local/hls/index.m3u8", httpsPage),
  "https://phone.local/hls/index.m3u8"
);
// HTTPS page + HTTP stream -> reject
assert.equal(
  Receiver.normalizeStreamUrl("http://phone.local/hls/index.m3u8", httpsPage),
  null
);

// ---- invalid URL ----
assert.equal(Receiver.normalizeStreamUrl("not a url", httpPage), null);
assert.equal(Receiver.normalizeStreamUrl("", httpPage), null);
assert.equal(Receiver.normalizeStreamUrl(null, httpPage), null);

// ---- unsupported protocols ----
assert.equal(Receiver.normalizeStreamUrl("ftp://phone.local/hls/index.m3u8", httpPage), null);
assert.equal(Receiver.normalizeStreamUrl("javascript:alert(1)", httpPage), null);
assert.equal(Receiver.normalizeStreamUrl("data:text/plain,hi", httpPage), null);

// ---- credentials ----
assert.equal(Receiver.normalizeStreamUrl("http://user:pass@phone.local/hls/index.m3u8", httpPage), null);
assert.equal(Receiver.normalizeStreamUrl("http://user@phone.local/hls/index.m3u8", httpPage), null);
assert.equal(Receiver.normalizeStreamUrl("http://@example.com/", httpPage), null);

// ---- ports ----
assert.equal(
  Receiver.normalizeStreamUrl("http://example.com:80/", httpPage),
  "http://example.com/"
);
assert.equal(
  Receiver.normalizeStreamUrl("http://example.com:443/", httpPage),
  "http://example.com:443/"
);
assert.equal(
  Receiver.normalizeStreamUrl("http://example.com:8765/", httpPage),
  "http://example.com:8765/"
);
assert.equal(Receiver.normalizeStreamUrl("http://example.com:65536/", httpPage), null);
assert.equal(
  Receiver.normalizeStreamUrl("http://example.com:/", httpPage),
  "http://example.com/"
);

// ---- IPv6 ----
assert.equal(
  Receiver.normalizeStreamUrl("http://[::1]:8765/", httpPage),
  "http://[::1]:8765/"
);
assert.equal(
  Receiver.normalizeStreamUrl("http://[::1]/", httpPage),
  "http://[::1]/"
);

// ---- credentials (additional) ----
assert.equal(Receiver.normalizeStreamUrl("http://user:pass@example.com/", httpPage), null);

// ---- ports ----
assert.equal(
  Receiver.normalizeStreamUrl("http://phone.local:8765/hls/index.m3u8", httpPage),
  "http://phone.local:8765/hls/index.m3u8"
);
// invalid port (out of range) -> URL normalizes port to empty -> reject
assert.equal(Receiver.normalizeStreamUrl("http://phone.local:99999/hls/index.m3u8", httpPage), null);
// no port specified -> accept
assert.equal(
  Receiver.normalizeStreamUrl("http://phone.local/hls/index.m3u8", httpPage),
  "http://phone.local/hls/index.m3u8"
);

// ---- no "hls" in path (should still be valid if protocol matches) ----
assert.equal(
  Receiver.normalizeStreamUrl("http://phone.local/stream.m3u8", httpPage),
  "http://phone.local/stream.m3u8"
);
assert.equal(
  Receiver.normalizeStreamUrl("https://phone.local/playlist.m3u8", httpsPage),
  "https://phone.local/playlist.m3u8"
);

// ---- pageLocation unavailable -> not assumed HTTPS ----
assert.equal(
  Receiver.normalizeStreamUrl("http://phone.local/hls/index.m3u8", null),
  "http://phone.local/hls/index.m3u8"
);
assert.equal(
  Receiver.normalizeStreamUrl("http://phone.local/hls/index.m3u8", undefined),
  "http://phone.local/hls/index.m3u8"
);

// ---- stream_url handler behavior ----
function makeReceiver(pageLocation) {
  // Override location for this test by passing pageLocation to normalizeStreamUrl inside handler?
  // The handler uses typeof location; we set global location.
  global.location = pageLocation;
  const observed = { ready: [], subtitles: [], streams: [], errors: [] };
  const receiver = new Receiver({
    onReady: (v) => observed.ready.push(v),
    onSubtitle: (v) => observed.subtitles.push(v),
    onStreamUrl: (v) => observed.streams.push(v),
    onError: (v) => observed.errors.push(v),
    onCaptions: () => {}
  });
  return { receiver, observed };
}

// Accept case: HTTP page, HTTP stream
{
  global.location = httpPage;
  const { receiver, observed } = makeReceiver(httpPage);
  const sockets = [];
  const origWS = global.WebSocket;
  function FakeWS(url) { this.url = url; this.readyState = 0; this.binaryType = ""; this.sent = []; sockets.push(this); }
  FakeWS.prototype.send = function (v) { this.sent.push(v); };
  FakeWS.prototype.open = function () { this.readyState = 1; if (this.onopen) this.onopen(); };
  FakeWS.prototype.message = function (d) { if (this.onmessage) this.onmessage({ data: d }); };
  FakeWS.prototype.close = function (c, r) { this.readyState = 3; if (this.onclose) this.onclose({ code: c, reason: r }); };
  global.WebSocket = FakeWS;
  receiver.connect("192.168.1.5", "123456");
  const socket = sockets[0];
  socket.open();
  socket.message(JSON.stringify({ v: Receiver.PROTOCOL_VERSION, kind: "ready" }));
  assert.equal(observed.ready[observed.ready.length - 1], true);
  // stream_url accepted
  socket.message(JSON.stringify({ v: Receiver.PROTOCOL_VERSION, kind: "stream_url", url: "http://phone.local/hls/index.m3u8" }));
  assert.equal(observed.streams.length, 1);
  assert.equal(observed.streams[0].url, "http://phone.local/hls/index.m3u8");
  assert.equal(observed.errors.length, 0);
  // WebSocket still open
  assert.equal(socket.readyState, 1);
  // subtitle still works
  socket.message(JSON.stringify({ v: Receiver.PROTOCOL_VERSION, kind: "subtitle", id: "s1", game: "g", source: "src", translation: "tr", timestamp: 123 }));
  assert.equal(observed.subtitles.length, 1);
  // captions
  let captionsReceived = false;
  receiver.options.onCaptions = () => { captionsReceived = true; };
  socket.message(JSON.stringify({ v: Receiver.PROTOCOL_VERSION, kind: "captions", lines: ["a"] }));
  assert.equal(captionsReceived, true);
  // disconnect
  receiver.disconnect();
  assert.equal(receiver.ready, false);
  global.WebSocket = origWS;
}

// Reject case: HTTPS page, HTTP stream -> onError, no onStreamUrl, socket stays open
{
  global.location = httpsPage;
  const { receiver, observed } = makeReceiver(httpsPage);
  const sockets = [];
  function FakeWS2(url) { this.url = url; this.readyState = 0; this.binaryType = ""; this.sent = []; sockets.push(this); }
  FakeWS2.prototype.send = function (v) { this.sent.push(v); };
  FakeWS2.prototype.open = function () { this.readyState = 1; if (this.onopen) this.onopen(); };
  FakeWS2.prototype.message = function (d) { if (this.onmessage) this.onmessage({ data: d }); };
  FakeWS2.prototype.close = function (c, r) { this.readyState = 3; if (this.onclose) this.onclose({ code: c, reason: r }); };
  global.WebSocket = FakeWS2;
  receiver.connect("192.168.1.5", "123456");
  const socket = sockets[0];
  socket.open();
  socket.message(JSON.stringify({ v: Receiver.PROTOCOL_VERSION, kind: "ready" }));
  const streamsBefore = observed.streams.length;
  const errorsBefore = observed.errors.length;
  socket.message(JSON.stringify({ v: Receiver.PROTOCOL_VERSION, kind: "stream_url", url: "http://phone.local/hls/index.m3u8" }));
  assert.equal(observed.streams.length, streamsBefore); // not emitted
  assert.equal(observed.errors.length, errorsBefore + 1); // onError emitted
  assert.equal(socket.readyState, 1); // socket still open
  receiver.disconnect();
  global.WebSocket = undefined;
}

console.log("PS Link Web receiver tests passed");

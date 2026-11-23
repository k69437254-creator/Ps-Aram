/* PSLINK_XBOX_LIFECYCLE_TESTS_V1 */
"use strict";

const assert = require("node:assert/strict");
const Receiver = require("../pslink-client.js");

class FakeWebSocket {
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    FakeWebSocket.instances.push(this);
  }
  send(value) { this.sent.push(value); }
  close(code, reason) {
    this.readyState = 3;
    this.closeCode = code;
    this.closeReason = reason;
  }
}
FakeWebSocket.instances = [];

global.WebSocket = FakeWebSocket;

function message(socket, value) {
  socket.onmessage({ data: JSON.stringify(value) });
}

async function main() {
  const events = [];
  const receiver = new Receiver({
    onReady: value => events.push(["ready", value]),
    onSubtitle: value => events.push(["subtitle", value]),
    onStreamUrl: value => events.push(["stream", value]),
    onError: value => events.push(["error", value])
  });

  receiver.connect("192.168.1.20", "١٢٣٤٥٦");

  const socket = FakeWebSocket.instances.at(-1);
  assert.equal(socket.url, "ws://192.168.1.20:8765/subtitle");

  socket.readyState = 1;
  socket.onopen();

  const auth = JSON.parse(socket.sent[0]);
  assert.equal(auth.kind, "auth");
  assert.equal(auth.code, "123456");

  message(socket, {v:2, kind:"ready"});
  assert.equal(receiver.ready, true);
  assert.deepEqual(events.at(-1), ["ready", true]);

  message(socket, {
    v:2, kind:"subtitle", id:"sub-1",
    game:"Game", source:"Hello", translation:"مرحبا",
    timestamp:123
  });

  assert.equal(events.at(-1)[0], "subtitle");
  assert.equal(events.at(-1)[1].translation, "مرحبا");

  const ack = JSON.parse(socket.sent.at(-1));
  assert.equal(ack.kind, "ack");
  assert.equal(ack.id, "sub-1");

  message(socket, {
    v:2, kind:"stream_url",
    url:"http://192.168.1.20:8765/hls/index.m3u8"
  });

  assert.equal(events.at(-1)[0], "stream");
  assert.match(events.at(-1)[1].url, /\/hls\/index\.m3u8$/);

  receiver.disconnect();
  assert.equal(receiver.ready, false);
  assert.equal(receiver.closedByUser, true);
  assert.equal(socket.closeCode, 1000);

  console.log("PASS: Xbox WebSocket authentication lifecycle.");
  console.log("PASS: Xbox subtitle delivery and ACK.");
  console.log("PASS: Xbox HLS stream URL delivery.");
  console.log("PASS: Xbox explicit disconnect cleanup.");

  const rejected = new Receiver({
    onError: value => events.push(["error", value])
  });

  rejected.connect("192.168.1.20", "123456");
  const rejectedSocket = FakeWebSocket.instances.at(-1);
  rejectedSocket.readyState = 1;
  rejectedSocket.onopen();

  message(rejectedSocket, {
    v:2, kind:"error", message:"Invalid pairing code"
  });

  assert.equal(rejected.authRejected, true);
  assert.equal(rejected.ready, false);
  rejectedSocket.onclose();
  assert.equal(rejected.reconnectTimer, null);

  console.log("PASS: Rejected pairing does not schedule reconnect.");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});

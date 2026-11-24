/* SPDX-License-Identifier: AGPL-3.0-only */
/* PS Link protocol v2 transport core for browser-based TV runtimes. */
(function (root) {
  "use strict";

  var PROTOCOL_VERSION = 2;
  var PORT = 8765;
  var PATH = "/subtitle";
  var MEDIA_MAGIC = 0x50534C4D;
  var MEDIA_VERSION = 1;
  var MEDIA_HEADER_BYTES = 28;
  var MAX_MEDIA_BYTES = 2 * 1024 * 1024;

  function normalizePairingCode(value) {
    var digits = String(value == null ? "" : value)
      .replace(/[٠-٩۰-۹]/g, function (ch) {
        var code = ch.charCodeAt(0);
        return String(code >= 0x06F0 ? code - 0x06F0 : code - 0x0660);
      })
      .replace(/[\s-]/g, "");

    return /^[0-9]{6}$/.test(digits) ? digits : null;
  }

  function normalizeStreamUrl(value, pageLocation) {
    var input = String(value || "").trim();
    var loc = pageLocation ||
      (typeof location !== "undefined" ? location : null);

    var parsed;
    try {
      parsed = new URL(input);
    } catch (error) {
      return null;
    }

    if (parsed.protocol !== "http:" &&
        parsed.protocol !== "https:") {
      return null;
    }

    // Reject URLs containing credentials (including empty @)
    if (parsed.username || parsed.password) {
      return null;
    }
    // Also reject raw @ in authority (handles http://@example.com)
    var authority = input.split("://")[1].split("/")[0];
    if (authority.indexOf("@") !== -1) {
      return null;
    }

    // Invalid ports throw in URL constructor; no extra check needed.
    // parsed.port is empty for default ports (80/443), which is valid.

    var securePage = !!(loc && loc.protocol === "https:");

    // On HTTPS pages, only HTTPS HLS playlists are safe. HTTP HLS is
    // rejected (no proxy is implemented in this transport). On HTTP pages,
    // both HTTP and HTTPS URLs pass through unchanged.
    if (securePage && parsed.protocol === "http:") {
      return null;
    }

    return parsed.href;
  }

  function parseMediaPacket(input) {
    var bytes = input instanceof ArrayBuffer
      ? new Uint8Array(input)
      : (input && input.buffer instanceof ArrayBuffer
        ? new Uint8Array(input.buffer, input.byteOffset || 0, input.byteLength)
        : null);

    if (!bytes ||
        bytes.byteLength < MEDIA_HEADER_BYTES + 1 ||
        bytes.byteLength > MEDIA_HEADER_BYTES + MAX_MEDIA_BYTES) {
      return null;
    }

    var view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

    if (view.getUint32(0, false) !== MEDIA_MAGIC ||
        view.getUint8(4) !== MEDIA_VERSION) {
      return null;
    }

    var type = view.getUint8(5);
    var flags = view.getUint16(6, false);
    var ptsNs;

    if (typeof view.getBigUint64 === "function") {
      ptsNs = Number(view.getBigUint64(8, false));
    } else {
      var hi = view.getUint32(8, false);
      var lo = view.getUint32(12, false);
      ptsNs = hi * 4294967296 + lo;
    }

    var arg1 = view.getInt32(16, false);
    var arg2 = view.getInt32(20, false);
    var length = view.getUint32(24, false);

    if ((type !== 1 && type !== 2) ||
        !Number.isSafeInteger(ptsNs) ||
        ptsNs <= 0 ||
        length <= 0 ||
        length !== bytes.byteLength - MEDIA_HEADER_BYTES) {
      return null;
    }

    if (type === 2 &&
        (arg1 < 8000 || arg1 > 192000 ||
         arg2 < 1 || arg2 > 2 ||
         length % (arg2 * 2) !== 0)) {
      return null;
    }

    return {
      type: type === 1 ? "video" : "audio",
      flags: flags,
      keyFrame: type === 1 && (flags & 1) !== 0,
      ptsNs: ptsNs,
      sampleRate: type === 2 ? arg1 : 0,
      channels: type === 2 ? arg2 : 0,
      payload: bytes.slice(MEDIA_HEADER_BYTES)
    };
  }

  function PsLinkReceiver(options) {
    this.options = options || {};
    this.socket = null;
    this.closedByUser = false;
    this.ready = false;
    this.lastConfig = null;
    this.host = "";
    this.code = "";
    this.authRejected = false;
    this.reconnectTimer = null;
    this.reconnectDelay = 1000;
    this.maxReconnectDelay = 30000;
  }

  PsLinkReceiver.prototype._emit = function (name, value) {
    var callback = this.options[name];
    if (typeof callback === "function") callback(value);
  };

  PsLinkReceiver.prototype._scheduleReconnect = function () {
    if (this.closedByUser || this.authRejected || this.reconnectTimer || !this.host || !this.code) return;
    var self = this;
    var delay = this.reconnectDelay;
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.maxReconnectDelay);
    this._emit("onStatus", "انقطع اتصال الشبكة؛ ستتم إعادة المحاولة تلقائيًا.");
    this.reconnectTimer = setTimeout(function () {
      self.reconnectTimer = null;
      self._openSocket();
    }, delay);
  };

  PsLinkReceiver.prototype._openSocket = function () {
    if (this.closedByUser || this.authRejected || !this.host || !this.code) return;
    var self = this;
    var socket;
    try {
      socket = new WebSocket("ws://" + this.host + ":" + PORT + PATH);
    } catch (error) {
      this._emit("onStatus", "تعذر فتح الاتصال؛ ستتم إعادة المحاولة.");
      this._scheduleReconnect();
      return;
    }

    this.socket = socket;
    socket.binaryType = "arraybuffer";

    socket.onopen = function () {
      if (self.socket !== socket || self.closedByUser) return;
      try {
        socket.send(JSON.stringify({
          v: PROTOCOL_VERSION,
          kind: "auth",
          code: self.code,
          client: "PS Link TV Web"
        }));
        self._emit("onStatus", "تم الاتصال؛ جارٍ التحقق من رمز الاقتران.");
      } catch (error) {
        try { socket.close(); } catch (ignore) {}
      }
    };

    socket.onmessage = function (event) {
      if (self.socket !== socket) return;
      if (typeof event.data !== "string") {
        var media = parseMediaPacket(event.data);
        if (media) self._emit("onMediaPacket", media);
        return;
      }

      var message;
      try { message = JSON.parse(event.data); } catch (ignore) { return; }
      if (message.v !== PROTOCOL_VERSION) return;

      if (message.kind === "ready") {
        self.ready = true;
        self.authRejected = false;
        self.reconnectDelay = 1000;
        if (self.lastConfig && socket.readyState === 1) {
          try { socket.send(self.lastConfig); } catch (ignore) {}
        }
        self._emit("onReady", true);
        self._emit("onStatus", "تم الاقتران؛ استقبال التلفاز جاهز.");

      } else if (message.kind === "error") {
        self.ready = false;
        self.authRejected = true;
        self._emit("onReady", false);
        self._emit("onError", message);
        self._emit("onStatus", message.message || "رفض الهاتف الاقتران؛ تحقق من الرمز.");
        try { socket.close(1008, "Pairing rejected"); } catch (ignore) {}

      } else if (message.kind === "subtitle") {
        if (typeof message.id !== "string" || !message.id) return;
        self._emit("onSubtitle", {
          id: message.id,
          game: String(message.game || "").slice(0, 100),
          source: String(message.source || "").slice(0, 4000),
          translation: String(message.translation || "").slice(0, 4000),
          timestamp: Number(message.timestamp) || 0
        });
        try {
          socket.send(JSON.stringify({ v: PROTOCOL_VERSION, kind: "ack", id: message.id }));
        } catch (ignore) {}

      } else if (message.kind === "stream_url") {
        var streamUrl = normalizeStreamUrl(
          message.url,
          typeof location !== "undefined" ? location : null
        );

        if (streamUrl) {
          self._emit("onStreamUrl", { url: streamUrl });
        } else {
          self._emit("onError", {
            message: "عنوان بث HLS غير صالح أو غير آمن لهذه الصفحة."
          });
        }

      } else if (message.kind === "captions") {
        self._emit("onCaptions", message);
      }
    };

    socket.onerror = function () {
      if (self.socket === socket) self._emit("onStatus", "حدث خطأ في اتصال الشبكة.");
    };

    socket.onclose = function () {
      if (self.socket !== socket) return;
      self.socket = null;
      self.ready = false;
      self._emit("onReady", false);
      if (!self.closedByUser && !self.authRejected) self._scheduleReconnect();
    };
  };

  PsLinkReceiver.prototype.connect = function (host, code) {
    var normalized = normalizePairingCode(code);
    if (!normalized) throw new Error("رمز الاقتران يجب أن يتكون من 6 أرقام");
    host = String(host || "").trim().replace(/^wss?:\/\//i, "").replace(/\/$/, "");
    if (!host || /[\s/]/.test(host)) throw new Error("عنوان الهاتف غير صالح");

    this.disconnect();
    this.closedByUser = false;
    this.authRejected = false;
    this.host = host;
    this.code = normalized;
    this.reconnectDelay = 1000;
    this._openSocket();
  };

  PsLinkReceiver.prototype.configureMedia = function (
    width, height, codec, fps
  ) {
    if (width < 320 || width > 7680 ||
        height < 240 || height > 4320 ||
        (codec !== 0 && codec !== 1 && codec !== 2) ||
        fps < 15 || fps > 120) {
      throw new Error("إعدادات بث الوسائط غير صالحة");
    }

    this.lastConfig = JSON.stringify({
      v: PROTOCOL_VERSION,
      kind: "media_config",
      width: width,
      height: height,
      codec: codec,
      fps: fps
    });

    if (this.ready && this.socket && this.socket.readyState === 1) {
      this.socket.send(this.lastConfig);
    }
  };

  PsLinkReceiver.prototype.disconnect = function () {
    this.closedByUser = true;
    this.ready = false;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }

    var old = this.socket;
    this.socket = null;
    if (old) {
      old.onclose = null;
      old.onmessage = null;
      old.onerror = null;
      try { old.close(1000, "User disconnected"); } catch (ignore) {}
    }
    this._emit("onReady", false);
  };

  PsLinkReceiver.parseMediaPacket = parseMediaPacket;
  PsLinkReceiver.normalizePairingCode = normalizePairingCode;
  PsLinkReceiver.PROTOCOL_VERSION = PROTOCOL_VERSION;
  PsLinkReceiver.normalizeStreamUrl = normalizeStreamUrl;

  if (typeof module !== "undefined" && module.exports) {
    module.exports = PsLinkReceiver;
  }

  root.PSLinkReceiver = PsLinkReceiver;
}(typeof window !== "undefined" ? window : this));

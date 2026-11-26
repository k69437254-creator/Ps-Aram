/* SPDX-License-Identifier: AGPL-3.0-only */
/* PS Link browser media adapter (HLS.js aware). */
(function (root) {
  "use strict";

  function validUrl(value) {
    if (typeof value !== "string" || value.length > 4096) return false;
    try {
      var parsed = new URL(value);
      return (parsed.protocol === "http:" || parsed.protocol === "https:") &&
        !parsed.username && !parsed.password && !parsed.hash;
    } catch (error) {
      return false;
    }
  }

  function emitEvent(name, message) {
    root.dispatchEvent(new CustomEvent(name, {
      detail: String(message || "")
    }));
  }

  function emitError(message) {
    emitEvent("pslink-media-error", message || "Media playback failed");
  }

  function canPlayHlsNatively(node) {
    if (!node || typeof node.canPlayType !== "function") return false;
    try {
      return node.canPlayType("application/vnd.apple.mpegurl") !== "" ||
        node.canPlayType("application/x-mpegURL") !== "";
    } catch (error) {
      return false;
    }
  }

  function BrowserPlayer() {
    this.node = document.getElementById("game-video");
    this.generation = 0;
    this.onError = null;
    this.hls = null;
  }

  BrowserPlayer.prototype._attemptPlay = function (generation) {
    var self = this;
    var node = this.node;
    node.autoplay = true;
    node.playsInline = true;
    node.muted = false;
    node.volume = 1;

    var result;
    try {
      result = node.play();
    } catch (error) {
      emitError(error.message || error);
      return;
    }
    if (result && typeof result.catch === "function") {
      result.catch(function (error) {
        if (self.generation !== generation) return;
        if (error && error.name === "NotAllowedError" && !node.muted) {
          node.muted = true;
          try {
            var retry = node.play();
            if (retry && typeof retry.then === "function") {
              retry.then(function () {
                if (self.generation === generation) {
                  emitEvent("pslink-media-warning", "بدأ العرض مكتومًا بسبب سياسة المتصفح؛ فعّل الصوت من عناصر التحكم.");
                }
              }).catch(function (retryError) {
                if (self.generation === generation) emitError(retryError.message || retryError);
              });
              return;
            }
          } catch (retryError) {
            emitError(retryError.message || retryError);
            return;
          }
        }
        emitError((error && error.message) || error);
      });
    }
  };

  BrowserPlayer.prototype.start = function (url) {
    if (!validUrl(url)) throw new Error("عنوان بث HLS غير صالح");
    if (!this.node) throw new Error("عنصر تشغيل الفيديو غير موجود");

    this.stop();
    var generation = ++this.generation;
    var self = this;

    this.onError = function () {
      if (self.generation === generation) {
        var mediaError = self.node && self.node.error;
        emitError(mediaError ? "media error: " + mediaError.code :
          "تعذر تشغيل بث الوسائط في المتصفح");
      }
    };

    this.node.addEventListener("error", this.onError);

    // Native HLS (Safari, Edge, etc.)
    if (canPlayHlsNatively(this.node)) {
      this.node.src = url;
      this.node.load();
      this._attemptPlay(generation);
      return;
    }

    // HLS.js path (Chrome, Firefox)
    if (typeof root.Hls !== "undefined" && root.Hls.isSupported()) {
      try {
        this.hls = new root.Hls({
          enableWorker: true,
          lowLatencyMode: false,
          maxBufferLength: 30,
          maxMaxBufferLength: 60
        });
        this.hls.loadSource(url);
        this.hls.attachMedia(this.node);
        this.hls.on(root.Hls.Events.MANIFEST_PARSED, function () {
          if (self.generation !== generation) return;
          self._attemptPlay(generation);
        });
        this.hls.on(root.Hls.Events.ERROR, function (event, data) {
          if (self.generation !== generation) return;
          if (data && data.fatal) {
            emitError("HLS error: " + (data.type || "unknown") + " - " + (data.details || ""));
            self.stop();
          }
        });
      } catch (error) {
        emitError(error.message || error);
      }
      return;
    }

    // No HLS support
    emitError("المتصفح لا يدعم تشغيل HLS (استخدم Safari أو أضف HLS.js)");
  };

  BrowserPlayer.prototype.stop = function () {
    this.generation += 1;
    if (!this.node) return;
    if (this.onError) {
      this.node.removeEventListener("error", this.onError);
      this.onError = null;
    }
    if (this.hls) {
      try { this.hls.destroy(); } catch (ignore) {}
      this.hls = null;
    }
    try { this.node.pause(); } catch (ignore) {}
    this.node.removeAttribute("src");
    try { this.node.load(); } catch (ignore) {}
  };

  root.PSLinkMediaPlayer = function () { return new BrowserPlayer(); };
}(window));

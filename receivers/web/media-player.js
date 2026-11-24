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

  function emitError(message) {
    root.dispatchEvent(new CustomEvent("pslink-media-error", {
      detail: String(message || "Media playback failed")
    }));
  }

  function canPlayHlsNatively(node) {
    if (!node || typeof node.canPlayType !== "function") return false;
    try {
      return node.canPlayType('application/vnd.apple.mpegurl') !== "" ||
        node.canPlayType('application/x-mpegURL') !== "";
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
      this.node.autoplay = true;
      this.node.playsInline = true;
      this.node.volume = 1;
      this.node.load();
      var result;
      try { result = this.node.play(); } catch (error) { emitError(error.message || error); return; }
      if (result && typeof result.catch === "function") {
        result.catch(function (error) {
          if (self.generation === generation) emitError(error.message || error);
        });
      }
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
          self.node.autoplay = true;
          self.node.playsInline = true;
          self.node.volume = 1;
          var p = self.node.play();
          if (p && typeof p.catch === "function") {
            p.catch(function (error) {
              if (self.generation === generation) emitError(error.message || error);
            });
          }
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

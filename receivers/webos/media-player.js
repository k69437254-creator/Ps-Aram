/* SPDX-License-Identifier: AGPL-3.0-only */
/* PS Link media adapter with stale-callback protection. */
(function (root) {
  "use strict";

  function validUrl(value) {
    if (typeof value !== "string" || value.length > 4096) return false;
    try {
      var parsed = new URL(value);
      return (parsed.protocol === "http:" || parsed.protocol === "https:") &&
        !parsed.username && !parsed.password &&
        !parsed.hash;
    } catch (error) {
      return false;
    }
  }

  function emitError(message) {
    root.dispatchEvent(new CustomEvent("pslink-media-error", {
      detail: String(message || "Media playback failed")
    }));
  }

  function TizenPlayer() {
    this.active = false;
    this.generation = 0;
  }

  TizenPlayer.prototype.start = function (url) {
    if (!validUrl(url)) throw new Error("عنوان بث HLS غير صالح");

    var api = root.webapis && root.webapis.avplay;
    if (!api) throw new Error("واجهة Samsung AVPlay غير متاحة على هذا التلفاز");

    this.stop();
    var generation = ++this.generation;
    var playerObject = document.getElementById("av-player");
    if (!playerObject) {
      playerObject = document.createElement("object");
      playerObject.id = "av-player";
      playerObject.type = "application/avplayer";
      var stage = document.getElementById("video-stage");
      if (!stage) throw new Error("منطقة عرض الفيديو غير موجودة");
      stage.appendChild(playerObject);
    }

    var self = this;
    try {
      if (typeof api.setListener === "function") {
        api.setListener({
          onbufferingstart: function () {},
          onbufferingcomplete: function () {},
          onstreamcompleted: function () {
            if (self.generation === generation) self.active = false;
          },
          onerror: function (error) {
            if (self.generation === generation) {
              self.active = false;
              emitError(error || "AVPlay error");
            }
          }
        });
      }

      api.open(url);
      api.setDisplayRect(0, 0,
        Math.max(1, root.innerWidth || 1920),
        Math.max(1, root.innerHeight || 1080));

      if (typeof api.setDisplayMethod === "function") {
        api.setDisplayMethod("PLAYER_DISPLAY_MODE_LETTER_BOX");
      }

      api.prepareAsync(function () {
        if (self.generation !== generation) return;
        try {
          api.play();
          if (self.generation === generation) self.active = true;
        } catch (error) {
          if (self.generation === generation) {
            self.active = false;
            emitError(error.message || error);
          }
        }
      }, function (error) {
        if (self.generation === generation) {
          self.active = false;
          emitError(error || "AVPlay prepare failed");
        }
      });
    } catch (error) {
      if (this.generation === generation) this.stop();
      throw error;
    }
  };

  TizenPlayer.prototype.stop = function () {
    this.generation += 1;
    var api = root.webapis && root.webapis.avplay;
    if (api) {
      try { api.stop(); } catch (ignore) {}
      try { api.close(); } catch (ignore) {}
    }
    this.active = false;
  };

  /* PSLINK_WEBOS_MEDIA_EVENTS_V1 */
  function WebOsPlayer() {
    this.node = document.getElementById("game-video");
    this.generation = 0;
    this.listeners = null;
  }

  WebOsPlayer.prototype.start = function (url) {
    if (!validUrl(url)) throw new Error("عنوان بث HLS غير صالح");
    if (!this.node) throw new Error("عنصر تشغيل webOS غير موجود");

    this.stop();

    var generation = ++this.generation;
    var self = this;

    function current() {
      return self.generation === generation;
    }

    this.listeners = {
      error: function () {
        if (!current()) return;
        var mediaError = self.node && self.node.error;
        emitError(mediaError
          ? "webOS media error: " + mediaError.code
          : "تعذر تشغيل بث الوسائط على webOS");
      },
      waiting: function () {
        if (!current()) return;
        root.dispatchEvent(new CustomEvent("pslink-media-buffering", {
          detail: { active: true }
        }));
      },
      playing: function () {
        if (!current()) return;
        root.dispatchEvent(new CustomEvent("pslink-media-buffering", {
          detail: { active: false }
        }));
      },
      ended: function () {
        if (!current()) return;
        root.dispatchEvent(new CustomEvent("pslink-media-buffering", {
          detail: { active: false }
        }));
        root.dispatchEvent(new CustomEvent("pslink-media-ended", {
          detail: {}
        }));
      }
    };

    Object.keys(this.listeners).forEach(function (name) {
      self.node.addEventListener(name, self.listeners[name]);
    });

    this.node.src = url;
    this.node.autoplay = true;
    this.node.playsInline = true;
    this.node.volume = 1;

    try {
      this.node.load();
      var result = this.node.play();

      if (result && typeof result.catch === "function") {
        result.catch(function (error) {
          if (current()) emitError(error.message || error);
        });
      }
    } catch (error) {
      if (current()) this.stop();
      throw error;
    }
  };

  WebOsPlayer.prototype.stop = function () {
    this.generation += 1;
    if (!this.node) return;

    var self = this;
    if (this.listeners) {
      Object.keys(this.listeners).forEach(function (name) {
        try {
          self.node.removeEventListener(name, self.listeners[name]);
        } catch (ignore) {}
      });
      this.listeners = null;
    }

    try { this.node.pause(); } catch (ignore) {}
    try { this.node.removeAttribute("src"); } catch (ignore) {}
    try { this.node.load(); } catch (ignore) {}
  };

  function createPlayer() {
    var platform = document.body.getAttribute("data-platform");
    return platform === "tizen" ? new TizenPlayer() : new WebOsPlayer();
  }

  root.PSLinkMediaPlayer = createPlayer;
}(window));

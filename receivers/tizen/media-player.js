/* SPDX-License-Identifier: AGPL-3.0-only */
(function (root) {
  "use strict";

  function validUrl(value) {
    return typeof value === "string" &&
      /^https?:\/\/[^\s]+$/i.test(value);
  }

  function TizenPlayer() {
    this.active = false;
  }

  TizenPlayer.prototype.start = function (url) {
    if (!validUrl(url)) throw new Error("عنوان بث HLS غير صالح");

    var api = root.webapis && root.webapis.avplay;
    if (!api) {
      throw new Error("واجهة Samsung AVPlay غير متاحة على هذا التلفاز");
    }

    this.stop();

    try {
      var playerObject = document.getElementById("av-player");
      if (!playerObject) {
        playerObject = document.createElement("object");
        playerObject.id = "av-player";
        playerObject.type = "application/avplayer";
        document.getElementById("video-stage").appendChild(playerObject);
      }

      if (typeof api.setListener === "function") {
        api.setListener({
          onbufferingstart: function () {},
          onbufferingcomplete: function () {},
          onstreamcompleted: function () {},
          onerror: function (error) {
            root.dispatchEvent(new CustomEvent("pslink-media-error", {
              detail: String(error || "AVPlay error")
            }));
          }
        });
      }

      api.open(url);
      api.setDisplayRect(0, 0, 1920, 1080);

      if (typeof api.setDisplayMethod === "function") {
        api.setDisplayMethod("PLAYER_DISPLAY_MODE_LETTER_BOX");
      }

      var self = this;
      api.prepareAsync(function () {
        try {
          api.play();
          self.active = true;
        } catch (error) {
          root.dispatchEvent(new CustomEvent("pslink-media-error", {
            detail: error.message || String(error)
          }));
        }
      }, function (error) {
        root.dispatchEvent(new CustomEvent("pslink-media-error", {
          detail: String(error || "AVPlay prepare failed")
        }));
      });
    } catch (error) {
      this.stop();
      throw error;
    }
  };

  TizenPlayer.prototype.stop = function () {
    var api = root.webapis && root.webapis.avplay;
    if (api) {
      try { api.stop(); } catch (ignore) {}
      try { api.close(); } catch (ignore) {}
    }
    this.active = false;
  };

  function WebOsPlayer() {
    this.node = document.getElementById("game-video");
  }

  WebOsPlayer.prototype.start = function (url) {
    if (!validUrl(url)) throw new Error("عنوان بث HLS غير صالح");
    if (!this.node) throw new Error("عنصر تشغيل webOS غير موجود");

    this.stop();
    this.node.src = url;
    this.node.autoplay = true;
    this.node.playsInline = true;
    this.node.volume = 1;
    this.node.load();

    var result = this.node.play();
    if (result && typeof result.catch === "function") {
      result.catch(function (error) {
        root.dispatchEvent(new CustomEvent("pslink-media-error", {
          detail: error.message || String(error)
        }));
      });
    }
  };

  WebOsPlayer.prototype.stop = function () {
    if (!this.node) return;
    try { this.node.pause(); } catch (ignore) {}
    this.node.removeAttribute("src");
    try { this.node.load(); } catch (ignore) {}
  };

  function createPlayer() {
    var platform = document.body.getAttribute("data-platform");
    return platform === "tizen" ? new TizenPlayer() : new WebOsPlayer();
  }

  root.PSLinkMediaPlayer = createPlayer;
}(window));

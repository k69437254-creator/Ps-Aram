/* SPDX-License-Identifier: AGPL-3.0-only */
(function () {
  "use strict";

  var receiver = null;
  var statusNode = document.getElementById("status");
  var form = document.getElementById("pairing-form");
  var connectButton = document.getElementById("connect");
  var disconnectButton = document.getElementById("disconnect");
  var subtitleNode = document.getElementById("subtitle");
  var captionsNode = document.getElementById("captions");
  var mediaNotice = document.getElementById("media-notice");
  var playbackToolbar = document.getElementById("playback-toolbar");
  var playbackStatusNode = document.getElementById("playback-status");
  var playbackDisconnectButton = document.getElementById("playback-disconnect");
  var mediaPlayer = new window.PSLinkMediaPlayer();
  var streamUrlReceived = false;

  function status(message, state) {
    statusNode.textContent = message;
    statusNode.setAttribute("data-state", state || "info");
    if (playbackStatusNode) playbackStatusNode.textContent = message;
  }

  function setConnected(connected) {
    connectButton.disabled = connected;
    disconnectButton.disabled = !connected;
    document.getElementById("host").disabled = connected;
    document.getElementById("code").disabled = connected;
  }

  function setPlaybackMode(active) {
    if (active) {
      document.body.classList.add("is-playing");
      playbackToolbar.hidden = false;
      if (playbackDisconnectButton) playbackDisconnectButton.focus();
    } else {
      document.body.classList.remove("is-playing");
      playbackToolbar.hidden = true;
    }
  }

  function resetPlayback(showNotice) {
    if (streamUrlReceived || !playbackToolbar.hidden) {
      mediaPlayer.stop();
    }

    streamUrlReceived = false;
    setPlaybackMode(false);

    if (window.PSLinkSpatialCaptions) {
      window.PSLinkSpatialCaptions.clear();
    }
    captionsNode.textContent = "";

    mediaNotice.hidden = !showNotice;
    if (showNotice) {
      mediaNotice.textContent =
        "تعذر تشغيل بث الوسائط؛ تحقق من HLS واتصال الهاتف.";
    }
  }

  function showSubtitle(item) {
    document.getElementById("game").textContent = item.game || "PS Link";
    document.getElementById("source").textContent = item.source || "";
    document.getElementById("translation").textContent =
      item.translation || "";
    subtitleNode.hidden = false;
    document.getElementById("empty-subtitle").hidden = true;
  }

  /* PSLINK_SPATIAL_CAPTIONS_INTEGRATION_V3 */
  function showCaptions(message) {
    var target = document.getElementById("game-video") ||
      document.getElementById("video-stage");
    var spatialRendered = false;

    /*
     * The video-stage exists before playback. Spatial captions must only
     * be positioned over it after an HLS stream URL has been accepted.
     */
    if (streamUrlReceived && window.PSLinkSpatialCaptions) {
      try {
        if (target) {
          spatialRendered =
            window.PSLinkSpatialCaptions.render(message, target) === true;
        } else {
          window.PSLinkSpatialCaptions.clear();
        }
      } catch (error) {
        try {
          window.PSLinkSpatialCaptions.clear();
        } catch (ignore) {}
        spatialRendered = false;
      }
    } else if (window.PSLinkSpatialCaptions) {
      window.PSLinkSpatialCaptions.clear();
    }

    if (spatialRendered) {
      captionsNode.textContent = "";
      return;
    }

    var lines = message && Array.isArray(message.lines)
      ? message.lines : [];

    captionsNode.textContent = lines.slice(0, 3).map(function (line) {
      if (typeof line === "string") return line;
      if (!line || typeof line !== "object") return "";
      return String(line.text || line.translation || line.source || "");
    }).filter(Boolean).join("\\n");
  }

  function disconnect() {
    if (receiver) receiver.disconnect();
    resetPlayback(false);
    mediaNotice.hidden = true;
    receiver = null;
    setConnected(false);
    status("تم فصل الاتصال", "info");
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();

    var host = document.getElementById("host").value.trim();
    var code = document.getElementById("code").value.trim();

    if (!host || !/^[0-9٠-٩۰-۹\s-]{6,20}$/.test(code)) {
      status("أدخل عنوان IP ورمز اقتران من 6 أرقام", "error");
      return;
    }

    try {
      receiver = new window.PSLinkReceiver({
        onStatus: function (message) {
          status(message, "info");
        },
        onReady: function (ready) {
          setConnected(ready);

          // A lost or rejected WebSocket session must not leave stale HLS
          // playback running while the receiver is disconnected/reconnecting.
          if (!ready) resetPlayback(false);
        },
        onSubtitle: showSubtitle,
        onCaptions: showCaptions,
        onError: function (error) {
          status(String(error.message || "تعذر إتمام الاقتران"), "error");
        },
        onMediaPacket: function () {
          // Raw packets are not rendered by AVPlay; wait for the HLS URL.
          if (!streamUrlReceived) mediaNotice.hidden = false;
        },
        onStreamUrl: function (stream) {
          try {
            mediaPlayer.start(stream.url);
            streamUrlReceived = true;
            mediaNotice.hidden = true;
            setPlaybackMode(true);
            status("تم استلام عنوان البث؛ جارٍ تشغيل HLS.", "info");
          } catch (error) {
            resetPlayback(true);
            status(error.message || "تعذر تشغيل بث الوسائط", "error");
          }
        }
      });

      receiver.connect(host, code);
      status("جارٍ الاتصال والتحقق من رمز الاقتران…", "info");
    } catch (error) {
      status(error.message || "تعذر بدء الاتصال", "error");
      receiver = null;
    }
  });

  disconnectButton.addEventListener("click", disconnect);
  playbackDisconnectButton.addEventListener("click", disconnect);

  window.addEventListener("keydown", function (event) {
    var keyCode = event.keyCode || event.which || 0;
    var isBack =
      event.key === "Escape" ||
      event.key === "Back" ||
      event.key === "BrowserBack" ||
      keyCode === 27 ||
      keyCode === 10009;

    if (isBack) {
      event.preventDefault();
      disconnect();
    }
  });

  window.addEventListener("pslink-media-error", function (event) {
    resetPlayback(true);
    status(String(event.detail || "حدث خطأ في مشغل الوسائط"), "error");
  });

  window.addEventListener("pslink-media-ended", function (event) {
    resetPlayback(false);
    status(String(event.detail || "انتهى بث الوسائط"), "info");
  });

  window.addEventListener("pslink-media-buffering", function (event) {
    var detail = event.detail || {};
    if (detail.active && streamUrlReceived) {
      status("جارٍ تحميل بيانات الفيديو…", "info");
    } else if (streamUrlReceived && mediaPlayer.active) {
      status("الفيديو والصوت قيد التشغيل", "success");
    }
  });

  setConnected(false);
}());

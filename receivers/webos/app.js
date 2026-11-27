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
  var mediaPlayer = new window.PSLinkMediaPlayer();
  var streamUrlReceived = false;

  /* PSLINK_WEBOS_LIFECYCLE_REPAIR_V1 */
  function setPlaybackMode(active) {
    if (!document.body || !document.body.classList) return;
    if (active) document.body.classList.add("is-playing");
    else document.body.classList.remove("is-playing");
  }

  function clearCaptions() {
    if (window.PSLinkSpatialCaptions) {
      try { window.PSLinkSpatialCaptions.clear(); } catch (ignore) {}
    }
    captionsNode.textContent = "";
  }

  function resetPlayback() {
    streamUrlReceived = false;
    try { if (mediaPlayer) mediaPlayer.stop(); } catch (ignore) {}
    mediaNotice.hidden = true;
    clearCaptions();
    setPlaybackMode(false);
  }

  function status(message, state) {
    statusNode.textContent = message;
    statusNode.setAttribute("data-state", state || "info");
  }

  function setConnected(connected) {
    connectButton.disabled = connected;
    disconnectButton.disabled = !connected;
    document.getElementById("host").disabled = connected;
    document.getElementById("code").disabled = connected;
  }

  function showSubtitle(item) {
    document.getElementById("game").textContent = item.game || "PS Link";
    document.getElementById("source").textContent = item.source || "";
    document.getElementById("translation").textContent =
      item.translation || "";
    subtitleNode.hidden = false;
    document.getElementById("empty-subtitle").hidden = true;
  }

  /* PSLINK_WEBOS_SPATIAL_CAPTIONS_V3 */
  function showCaptions(message) {
    var target = document.getElementById("game-video") ||
      document.getElementById("video-stage");
    var spatialRendered = false;

    if (!streamUrlReceived) {
      if (window.PSLinkSpatialCaptions) {
        try { window.PSLinkSpatialCaptions.clear(); } catch (ignore) {}
      }
    } else if (window.PSLinkSpatialCaptions) {
      try {
        spatialRendered = target &&
          window.PSLinkSpatialCaptions.render(message, target) === true;
      } catch (error) {
        try { window.PSLinkSpatialCaptions.clear(); } catch (ignore) {}
      }
    }

    if (spatialRendered) {
      captionsNode.textContent = "";
      return;
    }

    if (streamUrlReceived && window.PSLinkSpatialCaptions) {
      try { window.PSLinkSpatialCaptions.clear(); } catch (ignore) {}
    }

    var lines = message && Array.isArray(message.lines)
      ? message.lines : [];

    captionsNode.textContent = lines.slice(0, 3).map(function (line) {
      if (typeof line === "string") return line;
      if (!line || typeof line !== "object") return "";
      return String(line.text || line.translation || line.source || "");
    }).filter(Boolean).join("\n");
  }

  function disconnect() {
    var activeReceiver = receiver;
    receiver = null;

    if (activeReceiver) {
      try { activeReceiver.disconnect(); }
      catch (error) {
        status(error.message || "تعذر فصل الاتصال", "error");
      }
    }

    resetPlayback();
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
          if (!ready) resetPlayback();
        },
        onSubtitle: showSubtitle,
        onCaptions: showCaptions,
        onError: function (error) {
          status(String(error.message || "تعذر إتمام الاقتران"), "error");
        },
        onMediaPacket: function () {
          // Raw transport packets are not rendered by the HTML video element.
          if (!streamUrlReceived) mediaNotice.hidden = false;
        },
        onStreamUrl: function (stream) {
          try {
            mediaPlayer.start(stream.url);
            streamUrlReceived = true;
            setPlaybackMode(true);
            mediaNotice.hidden = true;
            status("تم استلام عنوان البث؛ جارٍ تشغيل الفيديو والصوت.", "info");
          } catch (error) {
            resetPlayback();
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

  window.addEventListener("keydown", function (event) {
    if (event.key === "Escape" || event.keyCode === 27) {
      disconnect();
    }
  });

  window.addEventListener("pslink-media-error", function (event) {
    resetPlayback();
    status(String(event.detail || "حدث خطأ في مشغل الوسائط"), "error");
  });

  window.addEventListener("pslink-media-ended", function () {
    if (!streamUrlReceived) return;
    resetPlayback();
    status("انتهى بث الوسائط.", "info");
  });

  window.addEventListener("pslink-media-buffering", function (event) {
    if (!streamUrlReceived) return;
    var buffering = !!(event.detail && event.detail.active);
    status(
      buffering ? "جارٍ تحميل الفيديو..." : "الفيديو يعمل الآن.",
      "info"
    );
  });

  setConnected(false);
}());

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

  function status(message, state) {
    statusNode.textContent = message;
    statusNode.setAttribute("data-state", state || "info");
  }

  var sessionActive = false;
  var sessionGeneration = 0;

  function setSessionActive(active) {
    sessionActive = active;
    connectButton.disabled = active;
    disconnectButton.disabled = !active;
    document.getElementById("host").disabled = active;
    document.getElementById("code").disabled = active;
  }

  function safeRun(callback) {
    try {
      callback();
    } catch (ignore) {}
  }

  function clearSpatialCaptions() {
    if (window.PSLinkSpatialCaptions) {
      safeRun(function () {
        window.PSLinkSpatialCaptions.clear();
      });
    }
  }

  function clearSessionDisplay() {
    safeRun(function () {
      document.getElementById("game").textContent = "PS Link";
    });

    safeRun(function () {
      document.getElementById("source").textContent = "";
    });

    safeRun(function () {
      document.getElementById("translation").textContent = "";
    });

    safeRun(function () {
      subtitleNode.hidden = true;
    });

    safeRun(function () {
      document.getElementById("empty-subtitle").hidden = false;
    });

    safeRun(function () {
      captionsNode.textContent = "";
    });

    safeRun(function () {
      mediaNotice.hidden = true;
    });
  }

  function showSubtitle(item) {
    document.getElementById("game").textContent = item.game || "PS Link";
    document.getElementById("source").textContent = item.source || "";
    document.getElementById("translation").textContent = item.translation || "";
    subtitleNode.hidden = false;
    document.getElementById("empty-subtitle").hidden = true;
  }

  function showCaptions(message) {
    var target = document.getElementById("game-video") ||
      document.getElementById("video-stage");
    var spatialRendered = false;

    if (window.PSLinkSpatialCaptions) {
      try {
        if (target) {
          spatialRendered =
            window.PSLinkSpatialCaptions.render(message, target) === true;
        } else {
          clearSpatialCaptions();
        }
      } catch (error) {
        clearSpatialCaptions();
        spatialRendered = false;
      }
    }

    if (spatialRendered) {
      captionsNode.textContent = "";
      return;
    }

    clearSpatialCaptions();

    var lines = message && Array.isArray(message.lines) ? message.lines : [];
    captionsNode.textContent = lines.slice(0, 3).map(function (line) {
      if (typeof line === "string") return line;
      if (!line || typeof line !== "object") return "";
      return String(line.text || line.translation || line.source || "");
    }).filter(Boolean).join("\n");
  }

  function cleanupSession(message, state) {
    // Invalidate stale callbacks before invoking external components.
    sessionGeneration += 1;

    var oldReceiver = receiver;
    receiver = null;

    safeRun(function () {
      setSessionActive(false);
    });

    if (oldReceiver) {
      safeRun(function () {
        oldReceiver.disconnect();
      });
    }

    if (mediaPlayer) {
      safeRun(function () {
        mediaPlayer.stop();
      });
    }

    clearSpatialCaptions();

    streamUrlReceived = false;
    clearSessionDisplay();

    safeRun(function () {
      status(message, state || "info");
    });
  }

  function disconnect() {
    cleanupSession("تم فصل الاتصال", "info");
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();

    // Prevent overlapping sessions, including synthetic duplicate submits.
    if (sessionActive) return;

    var host = document.getElementById("host").value.trim();
    var code = document.getElementById("code").value.trim();

    if (!host || !/^[0-9٠-٩۰-۹\s-]{6,20}$/.test(code)) {
      status("أدخل عنوان IP ورمز اقتران من 6 أرقام", "error");
      return;
    }

    var token = ++sessionGeneration;

    try {
      receiver = new window.PSLinkReceiver({
        onStatus: function (message) {
          if (token !== sessionGeneration) return;
          status(message, "info");
        },

        onReady: function (ready) {
          if (token !== sessionGeneration) return;
          if (ready) setSessionActive(true);
        },

        onSubtitle: function (item) {
          if (token !== sessionGeneration) return;
          showSubtitle(item);
        },

        onCaptions: function (message) {
          if (token !== sessionGeneration) return;
          showCaptions(message);
        },

        onError: function (error) {
          if (token !== sessionGeneration) return;

          var message = String(
            error && error.message || "تعذر إتمام الاقتران"
          );

          // Authentication rejection ends this session.
          if (receiver && receiver.authRejected) {
            cleanupSession(message, "error");
            return;
          }

          status(message, "error");
        },

        onMediaPacket: function () {
          if (token !== sessionGeneration) return;
          if (!streamUrlReceived) mediaNotice.hidden = false;
        },

        onStreamUrl: function (stream) {
          if (token !== sessionGeneration) return;

          try {
            mediaPlayer.start(stream.url);
            streamUrlReceived = true;
            mediaNotice.hidden = true;
            status("تم استلام عنوان البث؛ جارٍ تشغيل الفيديو.", "info");
          } catch (error) {
            streamUrlReceived = false;
            status(error.message || "تعذر تشغيل بث الوسائط", "error");
          }
        }
      });

      setSessionActive(true);
      receiver.connect(host, code);
      status("جارٍ الاتصال والتحقق من رمز الاقتران…", "info");

    } catch (error) {
      cleanupSession(
        error && error.message || "تعذر بدء الاتصال",
        "error"
      );
    }
  });

  disconnectButton.addEventListener("click", disconnect);

  window.addEventListener("keydown", function (event) {
    if ((event.key === "Escape" || event.keyCode === 27) && sessionActive) disconnect();
  });

  window.addEventListener("pslink-media-error", function (event) {
    status(String(event.detail || "حدث خطأ في مشغل الوسائط"), "error");
  });

  window.addEventListener("pslink-media-warning", function (event) {
    status(String(event.detail || ""), "info");
  });

  setSessionActive(false);
}());

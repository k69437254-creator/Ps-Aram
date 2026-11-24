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

  function setConnected(connected) {
    connectButton.disabled = connected;
    disconnectButton.disabled = !connected;
    document.getElementById("host").disabled = connected;
    document.getElementById("code").disabled = connected;
  }

  function showSubtitle(item) {
    document.getElementById("game").textContent = item.game || "PS Link";
    document.getElementById("source").textContent = item.source || "";
    document.getElementById("translation").textContent = item.translation || "";
    subtitleNode.hidden = false;
    document.getElementById("empty-subtitle").hidden = true;
  }

  function showCaptions(message) {
    var lines = message && Array.isArray(message.lines) ? message.lines : [];
    captionsNode.textContent = lines.slice(0, 3).map(function (line) {
      if (typeof line === "string") return line;
      if (!line || typeof line !== "object") return "";
      return String(line.text || line.translation || line.source || "");
    }).filter(Boolean).join("\n");
  }

  function disconnect() {
    if (receiver) receiver.disconnect();
    if (mediaPlayer) mediaPlayer.stop();
    streamUrlReceived = false;
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
        onStatus: function (message) { status(message, "info"); },
        onReady: function (ready) { setConnected(ready); },
        onSubtitle: showSubtitle,
        onCaptions: showCaptions,
        onError: function (error) {
          status(String(error.message || "تعذر إتمام الاقتران"), "error");
        },
        onMediaPacket: function () {
          if (!streamUrlReceived) mediaNotice.hidden = false;
        },
        onStreamUrl: function (stream) {
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
      receiver.connect(host, code);
      status("جارٍ الاتصال والتحقق من رمز الاقتران…", "info");
    } catch (error) {
      status(error.message || "تعذر بدء الاتصال", "error");
      receiver = null;
    }
  });

  disconnectButton.addEventListener("click", disconnect);

  window.addEventListener("keydown", function (event) {
    if (event.key === "Escape" || event.keyCode === 27) disconnect();
  });

  window.addEventListener("pslink-media-error", function (event) {
    status(String(event.detail || "حدث خطأ في مشغل الوسائط"), "error");
  });

  setConnected(false);
}());

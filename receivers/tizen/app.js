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

  function showCaptions(message) {
    var lines = Array.isArray(message.lines) ? message.lines : [];
    captionsNode.textContent = "";

    lines.slice(0, 8).forEach(function (line) {
      var row = document.createElement("div");
      row.className = "caption-line";
      row.textContent = typeof line === "string"
        ? line
        : String(line.text || line.translation || line.source || "");
      captionsNode.appendChild(row);
    });
  }

  function disconnect() {
    if (receiver) receiver.disconnect();
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
        },
        onSubtitle: showSubtitle,
        onCaptions: showCaptions,
        onError: function (error) {
          status(String(error.message || "تعذر إتمام الاقتران"), "error");
        },
        onMediaPacket: function () {
          mediaNotice.hidden = false;
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

  setConnected(false);
}());

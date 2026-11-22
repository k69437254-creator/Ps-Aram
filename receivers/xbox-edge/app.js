(function () {
  "use strict";

  var receiver = null;
  var video = document.getElementById("video");
  var status = document.getElementById("status");
  var form = document.getElementById("pairing-form");
  var connect = document.getElementById("connect");
  var disconnect = document.getElementById("disconnect");
  var hls = null;

  function setStatus(message) {
    status.textContent = message;
  }

  function stopVideo() {
    if (hls) {
      hls.destroy();
      hls = null;
    }
    video.pause();
    video.removeAttribute("src");
    video.load();
  }

  function playStream(url) {
    stopVideo();

    if (window.Hls && Hls.isSupported()) {
      hls = new Hls();
      hls.loadSource(url);
      hls.attachMedia(video);
      hls.on(Hls.Events.ERROR, function (_event, data) {
        if (data && data.fatal) {
          setStatus("تعذر تشغيل HLS في المتصفح");
        }
      });
    } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url;
      video.play().catch(function () {
        setStatus("اضغط تشغيل الفيديو لبدء العرض");
      });
    } else {
      setStatus("هذا المتصفح لا يدعم تشغيل HLS");
    }
  }

  form.addEventListener("submit", function (event) {
    event.preventDefault();

    var host = document.getElementById("host").value.trim();
    var code = document.getElementById("code").value.trim();

    try {
      if (receiver) receiver.disconnect();

      receiver = new window.PSLinkReceiver({
        onStatus: setStatus,
        onReady: function (ready) {
          connect.disabled = ready;
          disconnect.disabled = !ready;
        },
        onError: function (error) {
          setStatus(error.message || "فشل الاقتران");
        },
        onSubtitle: function (item) {
          document.getElementById("game").textContent = item.game || "PS Link";
          document.getElementById("translation").textContent =
            item.translation || "";
        },
        onCaptions: function (message) {
          var lines = Array.isArray(message.lines) ? message.lines : [];
          document.getElementById("captions").textContent =
            lines.map(function (line) {
              return typeof line === "string"
                ? line
                : String(line.text || line.translation || "");
            }).join("\n");
        },
        onStreamUrl: function (stream) {
          playStream(stream.url);
        }
      });

      receiver.connect(host, code);
      setStatus("جارٍ الاتصال بالهاتف...");
    } catch (error) {
      setStatus(error.message || "تعذر بدء الاتصال");
    }
  });

  disconnect.addEventListener("click", function () {
    if (receiver) receiver.disconnect();
    receiver = null;
    stopVideo();
    connect.disabled = false;
    disconnect.disabled = true;
    setStatus("تم قطع الاتصال");
  });
}());

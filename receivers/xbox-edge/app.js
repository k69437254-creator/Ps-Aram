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

  function attemptPlay() {
    video.muted = false;
    video.volume = 1;
    var playPromise = video.play();
    if (playPromise && typeof playPromise.catch === "function") {
      playPromise.catch(function (error) {
        if (error && error.name === "NotAllowedError" && !video.muted) {
          video.muted = true;
          video.play().then(function () {
            setStatus("بدأ العرض مكتومًا بسبب سياسة المتصفح؛ فعّل الصوت من عناصر التحكم.");
          }).catch(function () {
            setStatus("اضغط تشغيل الفيديو لبدء العرض");
          });
        } else {
          setStatus("اضغط تشغيل الفيديو لبدء العرض");
        }
      });
    }
  }

  function playStream(url) {
    stopVideo();

    if (window.Hls && Hls.isSupported()) {
      hls = new Hls({
        enableWorker: true,
        lowLatencyMode: false,
        maxBufferLength: 30,
        maxMaxBufferLength: 60
      });
      hls.loadSource(url);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, function () {
        attemptPlay();
      });
      hls.on(Hls.Events.ERROR, function (_event, data) {
        if (data && data.fatal) {
          setStatus("تعذر تشغيل HLS في المتصفح");
        }
      });
    } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
      video.src = url;
      attemptPlay();
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
          var captionsNode = document.getElementById("captions");
          var spatialRendered = false;
          if (window.PSLinkSpatialCaptions) {
            try {
              spatialRendered = window.PSLinkSpatialCaptions.render(message, video) === true;
            } catch (error) {
              try { window.PSLinkSpatialCaptions.clear(); } catch (ignore) {}
              spatialRendered = false;
            }
          }
          if (spatialRendered) {
            captionsNode.textContent = "";
            return;
          }
          if (window.PSLinkSpatialCaptions) {
            window.PSLinkSpatialCaptions.clear();
          }
          var lines = Array.isArray(message.lines) ? message.lines : [];
          captionsNode.textContent =
            lines.slice(0, 3).map(function (line) {
              return typeof line === "string"
                ? line
                : String(line.text || line.translation || line.source || "");
            }).filter(Boolean).join("\n");
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
    if (window.PSLinkSpatialCaptions) window.PSLinkSpatialCaptions.clear();

    document.getElementById("game").textContent = "PS Link";
    document.getElementById("translation").textContent = "";
    document.getElementById("captions").textContent = "";

    connect.disabled = false;
    disconnect.disabled = true;
    setStatus("تم قطع الاتصال");
  });
}());

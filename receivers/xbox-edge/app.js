(function () {
  "use strict";

  var receiver = null;
  var sessionGeneration = 0;
  var playbackGeneration = 0;
  var playbackActive = false;

  var video = document.getElementById("video");
  var status = document.getElementById("status");
  var form = document.getElementById("pairing-form");
  var connect = document.getElementById("connect");
  var disconnect = document.getElementById("disconnect");
  var hls = null;

  function setStatus(message) {
    status.textContent = message;
  }

  function clearCaptions() {
    if (window.PSLinkSpatialCaptions) {
      try { window.PSLinkSpatialCaptions.clear(); } catch (ignore) {}
    }
    document.getElementById("captions").textContent = "";
  }

  function stopVideo() {
    playbackGeneration++;
    playbackActive = false;

    if (hls) {
      try { hls.destroy(); } catch (ignore) {}
      hls = null;
    }

    try { video.pause(); } catch (ignore) {}
    video.removeAttribute("src");
    video.load();
  }

  function attemptPlay(generation) {
    if (!playbackActive || generation !== playbackGeneration) return;

    video.muted = false;
    video.volume = 1;

    var playPromise;
    try {
      playPromise = video.play();
    } catch (error) {
      if (generation === playbackGeneration) {
        setStatus("اضغط تشغيل الفيديو لبدء العرض");
      }
      return;
    }

    if (playPromise && typeof playPromise.catch === "function") {
      playPromise.catch(function (error) {
        if (!playbackActive || generation !== playbackGeneration) return;

        if (error && error.name === "NotAllowedError" && !video.muted) {
          video.muted = true;

          var mutedPromise;
          try {
            mutedPromise = video.play();
          } catch (ignore) {
            setStatus("اضغط تشغيل الفيديو لبدء العرض");
            return;
          }

          if (mutedPromise && typeof mutedPromise.then === "function") {
            mutedPromise.then(function () {
              if (playbackActive && generation === playbackGeneration) {
                setStatus("بدأ العرض مكتومًا بسبب سياسة المتصفح؛ فعّل الصوت من عناصر التحكم.");
              }
            }).catch(function () {
              if (playbackActive && generation === playbackGeneration) {
                setStatus("اضغط تشغيل الفيديو لبدء العرض");
              }
            });
          }
        } else {
          setStatus("اضغط تشغيل الفيديو لبدء العرض");
        }
      });
    }
  }

  function playStream(url) {
    stopVideo();

    var generation = playbackGeneration;
    playbackActive = true;

    var networkRecoveryUsed = false;
    var mediaRecoveryUsed = false;

    if (window.Hls && Hls.isSupported()) {
      var instance;

      try {
        instance = new Hls({
          enableWorker: true,
          lowLatencyMode: false,
          maxBufferLength: 30,
          maxMaxBufferLength: 60
        });
      } catch (error) {
        playbackActive = false;
        setStatus("تعذر تهيئة مشغل HLS");
        return;
      }

      hls = instance;

      instance.on(Hls.Events.MANIFEST_PARSED, function () {
        if (!playbackActive || generation !== playbackGeneration || hls !== instance) return;
        attemptPlay(generation);
      });

      instance.on(Hls.Events.ERROR, function (_event, data) {
        if (!playbackActive || generation !== playbackGeneration || hls !== instance) return;
        if (!data || !data.fatal) return;

        if (data.type === Hls.ErrorTypes.NETWORK_ERROR && !networkRecoveryUsed) {
          networkRecoveryUsed = true;
          setStatus("انقطع تحميل البث؛ جارٍ إعادة المحاولة...");
          try {
            instance.startLoad();
          } catch (error) {
            stopVideo();
            setStatus("تعذر استعادة اتصال بث HLS");
          }
          return;
        }

        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !mediaRecoveryUsed) {
          mediaRecoveryUsed = true;
          setStatus("حدث خلل في تشغيل الوسائط؛ جارٍ إصلاح المشغل...");
          try {
            instance.recoverMediaError();
          } catch (error) {
            stopVideo();
            setStatus("تعذر إصلاح تشغيل الوسائط");
          }
          return;
        }

        stopVideo();
        setStatus("تعذر تشغيل بث HLS؛ أعد الاتصال أو حاول تشغيل البث مجددًا.");
      });

      try {
        instance.loadSource(url);
        instance.attachMedia(video);
      } catch (error) {
        if (generation === playbackGeneration && hls === instance) {
          stopVideo();
          setStatus("تعذر بدء تحميل بث HLS");
        }
      }

    } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
      try {
        video.src = url;
        video.load();
        attemptPlay(generation);
      } catch (error) {
        stopVideo();
        setStatus("تعذر تشغيل بث الفيديو");
      }
    } else {
      playbackActive = false;
      setStatus("هذا المتصفح لا يدعم تشغيل HLS");
    }
  }

  video.addEventListener("waiting", function () {
    if (playbackActive) setStatus("جارٍ تحميل البث...");
  });

  video.addEventListener("stalled", function () {
    if (playbackActive) setStatus("توقف وصول بيانات الفيديو؛ جارٍ انتظار البث...");
  });

  video.addEventListener("playing", function () {
    if (playbackActive) setStatus("البث يعمل");
  });

  video.addEventListener("canplay", function () {
    if (playbackActive && video.paused) {
      setStatus("البث جاهز؛ اضغط تشغيل");
    }
  });

  video.addEventListener("ended", function () {
    if (playbackActive) {
      playbackActive = false;
      setStatus("انتهى البث");
    }
  });

  video.addEventListener("error", function () {
    if (playbackActive) {
      playbackActive = false;
      setStatus("حدث خطأ أثناء تشغيل الفيديو");
    }
  });

  form.addEventListener("submit", function (event) {
    event.preventDefault();

    var host = document.getElementById("host").value.trim();
    var code = document.getElementById("code").value.trim();

    var generation = ++sessionGeneration;

    if (receiver) {
      receiver.disconnect();
      receiver = null;
    }

    stopVideo();
    clearCaptions();

    document.getElementById("game").textContent = "PS Link";
    document.getElementById("translation").textContent = "";

    connect.disabled = true;
    disconnect.disabled = false;

    try {
      var currentReceiver = new window.PSLinkReceiver({
        onStatus: function (message) {
          if (generation !== sessionGeneration) return;
          setStatus(message);
        },

        onReady: function (ready) {
          if (generation !== sessionGeneration) return;
          connect.disabled = !!ready;
          disconnect.disabled = false;
        },

        onError: function (error) {
          if (generation !== sessionGeneration) return;
          setStatus(error && error.message ? error.message : "فشل الاقتران");
        },

        onSubtitle: function (item) {
          if (generation !== sessionGeneration) return;
          document.getElementById("game").textContent = item.game || "PS Link";
          document.getElementById("translation").textContent =
            item.translation || "";
        },

        onCaptions: function (message) {
          if (generation !== sessionGeneration) return;

          var captionsNode = document.getElementById("captions");
          var spatialRendered = false;

          if (window.PSLinkSpatialCaptions) {
            try {
              spatialRendered =
                window.PSLinkSpatialCaptions.render(message, video) === true;
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
          captionsNode.textContent = lines.slice(0, 3).map(function (line) {
            return typeof line === "string"
              ? line
              : String(line.text || line.translation || line.source || "");
          }).filter(Boolean).join("\n");
        },

        onStreamUrl: function (stream) {
          if (generation !== sessionGeneration) return;
          if (!stream || typeof stream.url !== "string") return;
          playStream(stream.url);
        }
      });

      receiver = currentReceiver;
      currentReceiver.connect(host, code);
      setStatus("جارٍ الاتصال بالهاتف...");

    } catch (error) {
      if (generation === sessionGeneration) {
        receiver = null;
        connect.disabled = false;
        disconnect.disabled = true;
        setStatus(error.message || "تعذر بدء الاتصال");
      }
    }
  });

  disconnect.addEventListener("click", function () {
    sessionGeneration++;

    if (receiver) {
      receiver.disconnect();
      receiver = null;
    }

    stopVideo();
    clearCaptions();

    document.getElementById("game").textContent = "PS Link";
    document.getElementById("translation").textContent = "";

    connect.disabled = false;
    disconnect.disabled = true;
    setStatus("تم قطع الاتصال");
  });
}());

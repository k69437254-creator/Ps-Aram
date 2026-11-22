(function () {
    "use strict";

    var platform = document.body.getAttribute("data-platform") || "TV";
    var output = document.getElementById("results");
    var runButton = document.getElementById("run");

    function report(name, result, detail) {
        var item = document.createElement("div");
        item.className = "result";
        item.innerHTML = "";

        var title = document.createElement("strong");
        title.textContent = name + ": " + result;

        var description = document.createElement("div");
        description.textContent = detail || "";

        item.appendChild(title);
        item.appendChild(description);
        output.appendChild(item);
    }

    function finish() {
        runButton.disabled = false;
        runButton.textContent = "Run tests again";
    }

    function run() {
        output.innerHTML = "";
        runButton.disabled = true;
        runButton.textContent = "Testing...";

        report("Platform", "INFO", platform);
        report("Browser", "INFO", navigator.userAgent);

        var Peer = window.RTCPeerConnection ||
                   window.webkitRTCPeerConnection;

        if (!Peer) {
            report("WebRTC", "FAILED",
                "RTCPeerConnection is not available.");
            finish();
            return;
        }

        report("WebRTC API", "AVAILABLE",
            "RTCPeerConnection exists.");

        var senderCaps = null;
        var receiverCaps = null;

        try {
            if (window.RTCRtpSender &&
                window.RTCRtpSender.getCapabilities) {
                senderCaps =
                    window.RTCRtpSender.getCapabilities("video");
            }

            if (window.RTCRtpReceiver &&
                window.RTCRtpReceiver.getCapabilities) {
                receiverCaps =
                    window.RTCRtpReceiver.getCapabilities("video");
            }

            var codecs = [];
            var sources = [senderCaps, receiverCaps];

            for (var s = 0; s < sources.length; s++) {
                if (!sources[s] || !sources[s].codecs) continue;

                for (var c = 0; c < sources[s].codecs.length; c++) {
                    var codec = sources[s].codecs[c];
                    var mime = codec.mimeType || "";

                    if (mime.toLowerCase() === "video/h264") {
                        codecs.push(mime);
                    }
                }
            }

            if (codecs.length) {
                report("H.264 capability", "ADVERTISED",
                    "H.264 appears in WebRTC codec capabilities.");
            } else {
                report("H.264 capability", "UNKNOWN",
                    "H.264 capability API is unavailable or did not list it.");
            }
        } catch (error) {
            report("H.264 capability", "UNKNOWN", error.message);
        }

        var pc1 = null;
        var pc2 = null;
        var completed = false;
        var timeout = null;

        function cleanup() {
            if (timeout) clearTimeout(timeout);

            try {
                if (pc1) pc1.close();
                if (pc2) pc2.close();
            } catch (ignore) {}
        }

        function end(success, detail) {
            if (completed) return;
            completed = true;
            cleanup();

            report("Local DataChannel test",
                success ? "PASSED" : "FAILED", detail);

            finish();
        }

        try {
            pc1 = new Peer(null);
            pc2 = new Peer(null);

            pc1.onicecandidate = function (event) {
                if (event.candidate && pc2) {
                    pc2.addIceCandidate(event.candidate)
                        .catch(function () {});
                }
            };

            pc2.onicecandidate = function (event) {
                if (event.candidate && pc1) {
                    pc1.addIceCandidate(event.candidate)
                        .catch(function () {});
                }
            };

            pc2.ondatachannel = function (event) {
                event.channel.onmessage = function (message) {
                    if (message.data === "PSLINK_WEBRTC_OK") {
                        end(true,
                            "Offer/Answer and DataChannel message exchanged.");
                    }
                };
            };

            var channel = pc1.createDataChannel("pslink-test");

            channel.onopen = function () {
                channel.send("PSLINK_WEBRTC_OK");
            };

            timeout = setTimeout(function () {
                end(false,
                    "Local peer connection did not complete within 15 seconds.");
            }, 15000);

            pc1.createOffer()
                .then(function (offer) {
                    return pc1.setLocalDescription(offer);
                })
                .then(function () {
                    return pc2.setRemoteDescription(pc1.localDescription);
                })
                .then(function () {
                    return pc2.createAnswer();
                })
                .then(function (answer) {
                    return pc2.setLocalDescription(answer);
                })
                .then(function () {
                    return pc1.setRemoteDescription(pc2.localDescription);
                })
                .catch(function (error) {
                    end(false, error.message || String(error));
                });

        } catch (error) {
            end(false, error.message || String(error));
        }
    }

    runButton.addEventListener("click", run);
    run();
})();

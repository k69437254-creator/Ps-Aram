/* PSLINK_SPATIAL_CAPTIONS_V1 */
(function (root) {
  "use strict";

  function clamp(value, min, max) {
    value = Number(value);
    if (!isFinite(value)) return min;
    return Math.max(min, Math.min(max, value));
  }

  function computeContentRect(box, sourceWidth, sourceHeight) {
    var left = Number(box.left) || 0;
    var top = Number(box.top) || 0;
    var width = Number(box.width) || 0;
    var height = Number(box.height) || 0;

    sourceWidth = Number(sourceWidth) || 1920;
    sourceHeight = Number(sourceHeight) || 1080;

    if (width <= 0 || height <= 0 ||
        sourceWidth <= 0 || sourceHeight <= 0) return null;

    /* object-fit: contain geometry */
    var scale = Math.min(width / sourceWidth, height / sourceHeight);
    var contentWidth = sourceWidth * scale;
    var contentHeight = sourceHeight * scale;

    return {
      left: left + (width - contentWidth) / 2,
      top: top + (height - contentHeight) / 2,
      width: contentWidth,
      height: contentHeight
    };
  }

  function mapLine(line, rect) {
    if (!line || !rect) return null;

    var text = String(line.text || line.translation || line.source || "");
    if (!text.trim()) return null;

    var left = clamp(line.left, 0, 1);
    var top = clamp(line.top, 0, 1);
    var right = clamp(line.right, 0, 1);
    var bottom = clamp(line.bottom, 0, 1);

    if (right <= left || bottom <= top) return null;

    return {
      text: text.slice(0, 1000),
      left: rect.left + left * rect.width,
      top: rect.top + top * rect.height,
      width: (right - left) * rect.width,
      height: (bottom - top) * rect.height,
      direction: line.direction === "ltr" ? "ltr" : "rtl"
    };
  }

  function clear() {
    if (typeof document === "undefined") return;
    var old = document.getElementById("pslink-spatial-overlay");
    if (old && old.parentNode) old.parentNode.removeChild(old);
  }

  function render(message, target) {
    if (typeof document === "undefined") return false;

    var lines = message && Array.isArray(message.lines)
      ? message.lines.slice(0, 3) : [];

    clear();
    if (!lines.length || !target ||
        typeof target.getBoundingClientRect !== "function") return false;

    var box = target.getBoundingClientRect();
    var sourceWidth = target.videoWidth || target.naturalWidth ||
      Number(target.getAttribute && target.getAttribute("data-video-width")) || 1920;
    var sourceHeight = target.videoHeight || target.naturalHeight ||
      Number(target.getAttribute && target.getAttribute("data-video-height")) || 1080;

    var rect = computeContentRect(box, sourceWidth, sourceHeight);
    if (!rect) return false;

    var layer = document.createElement("div");
    layer.id = "pslink-spatial-overlay";
    layer.setAttribute("aria-live", "off");
    layer.style.position = "fixed";
    layer.style.left = "0";
    layer.style.top = "0";
    layer.style.width = "100vw";
    layer.style.height = "100vh";
    layer.style.overflow = "hidden";
    layer.style.pointerEvents = "none";
    layer.style.zIndex = "2147483000";

    lines.forEach(function (line) {
      var mapped = mapLine(line, rect);
      if (!mapped) return;

      var item = document.createElement("div");
      item.className = "pslink-caption-box";
      item.textContent = mapped.text;
      item.setAttribute("dir", mapped.direction);
      item.style.position = "absolute";
      item.style.left = mapped.left + "px";
      item.style.top = mapped.top + "px";
      item.style.width = Math.max(24, mapped.width) + "px";
      item.style.height = Math.max(20, mapped.height) + "px";
      item.style.boxSizing = "border-box";
      item.style.display = "flex";
      item.style.alignItems = "center";
      item.style.justifyContent = "center";
      item.style.padding = "2px 7px";
      item.style.overflow = "hidden";
      item.style.textAlign = "center";
      item.style.fontSize = "clamp(16px, 2.2vw, 36px)";
      item.style.fontWeight = "700";
      item.style.lineHeight = "1.25";
      item.style.color = "#fff";
      item.style.background = "rgba(0,0,0,.84)";
      item.style.border = "1px solid rgba(255,255,255,.3)";
      item.style.borderRadius = "5px";
      item.style.textShadow = "0 1px 2px #000";
      item.style.unicodeBidi = "plaintext";
      layer.appendChild(item);
    });

    if (!layer.childNodes.length) return false;
    document.body.appendChild(layer);
    return true;
  }

  var api = {
    render: render,
    clear: clear,
    computeContentRect: computeContentRect,
    mapLine: mapLine
  };

  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  }
  root.PSLinkSpatialCaptions = api;
}(typeof window !== "undefined" ? window : this));

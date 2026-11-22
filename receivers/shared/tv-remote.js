/* SPDX-License-Identifier: AGPL-3.0-only */
(function () {
  "use strict";

  var selector =
    "button:not([disabled]),input:not([disabled])," +
    "select:not([disabled]),a[href],[tabindex]:not([tabindex='-1'])";

  function items() {
    return Array.prototype.slice.call(
      document.querySelectorAll(selector)
    ).filter(function (node) {
      return node.offsetWidth > 0 && node.offsetHeight > 0;
    });
  }

  function focusFirst() {
    var all = items();
    if (all.length && all.indexOf(document.activeElement) === -1) {
      all[0].focus();
    }
  }

  function move(direction) {
    var all = items();
    var current = document.activeElement;
    var index = all.indexOf(current);

    if (index < 0) {
      focusFirst();
      return;
    }

    var rect = current.getBoundingClientRect();
    var cx = rect.left + rect.width / 2;
    var cy = rect.top + rect.height / 2;

    var candidates = all.map(function (node) {
      var r = node.getBoundingClientRect();
      var x = r.left + r.width / 2;
      var y = r.top + r.height / 2;
      var dx = x - cx;
      var dy = y - cy;

      var valid =
        (direction === "left" && dx < 0) ||
        (direction === "right" && dx > 0) ||
        (direction === "up" && dy < 0) ||
        (direction === "down" && dy > 0);

      if (!valid) return null;

      var primary =
        direction === "left" || direction === "right"
          ? Math.abs(dx)
          : Math.abs(dy);

      var secondary =
        direction === "left" || direction === "right"
          ? Math.abs(dy)
          : Math.abs(dx);

      return {
        node: node,
        score: primary + secondary * 2
      };
    }).filter(Boolean).sort(function (a, b) {
      return a.score - b.score;
    });

    if (candidates.length) candidates[0].node.focus();
  }

  document.addEventListener("keydown", function (event) {
    var key = event.key || "";
    var keyCode = event.keyCode || event.which || 0;

    var direction = {
      ArrowLeft: "left",
      ArrowRight: "right",
      ArrowUp: "up",
      ArrowDown: "down"
    }[key];

    if (!direction) {
      direction = {
        37: "left",
        38: "up",
        39: "right",
        40: "down"
      }[keyCode];
    }

    if (direction) {
      event.preventDefault();
      move(direction);
      return;
    }

    if (key === "Enter" || key === " " || keyCode === 13 || keyCode === 32) {
      var active = document.activeElement;
      if (active && active.tagName === "BUTTON") {
        event.preventDefault();
        active.click();
      }
    }
  });

  window.addEventListener("load", focusFirst);
}());

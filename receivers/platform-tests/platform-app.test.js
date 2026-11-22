/* SPDX-License-Identifier: AGPL-3.0-only */
"use strict";

var fs = require("fs");
var path = require("path");
var root = path.resolve(__dirname, "../..");

["tizen", "webos"].forEach(function (name) {
  var dir = path.join(root, "receivers", name);

  [
    "index.html",
    "app.css",
    "app.js",
    "pslink-client.js",
    "README.md"
  ].forEach(function (file) {
    if (!fs.existsSync(path.join(dir, file))) {
      throw new Error(name + " missing " + file);
    }
  });

  var html = fs.readFileSync(path.join(dir, "index.html"), "utf8");
  var app = fs.readFileSync(path.join(dir, "app.js"), "utf8");
  var client = fs.readFileSync(path.join(dir, "pslink-client.js"), "utf8");
  var shared = fs.readFileSync(
    path.join(root, "receivers", "web", "pslink-client.js"), "utf8"
  );

  [
    "pairing-form",
    "host",
    "code",
    "status",
    "translation",
    "captions"
  ].forEach(function (id) {
    if (html.indexOf('id="' + id + '"') === -1) {
      throw new Error(name + " missing UI element " + id);
    }
  });

  [
    "PSLinkReceiver",
    "onStatus",
    "onReady",
    "onError",
    "onSubtitle",
    "onCaptions",
    "onMediaPacket",
    "receiver.connect(host, code)",
    "disconnect"
  ].forEach(function (token) {
    if (app.indexOf(token) === -1) {
      throw new Error(name + " missing adapter behavior " + token);
    }
  });

  if (client !== shared) {
    throw new Error(name + " transport client differs from shared client");
  }

  [
    "function PsLinkReceiver(options)",
    "PsLinkReceiver.prototype.connect = function",
    "PsLinkReceiver.prototype.disconnect = function",
    "root.PSLinkReceiver = PsLinkReceiver"
  ].forEach(function (token) {
    if (shared.indexOf(token) === -1) {
      throw new Error("Shared transport API missing " + token);
    }
  });

  if (html.indexOf('src="pslink-client.js"') === -1) {
    throw new Error(name + " does not load packaged transport client");
  }
});

var tizenManifest = fs.readFileSync(
  path.join(root, "receivers", "tizen", "config.xml"), "utf8"
);
var webosManifest = JSON.parse(fs.readFileSync(
  path.join(root, "receivers", "webos", "appinfo.json"), "utf8"
));

if (tizenManifest.indexOf("tv-samsung") === -1 ||
    tizenManifest.indexOf("index.html") === -1) {
  throw new Error("Tizen TV manifest is incomplete");
}

if (webosManifest.type !== "web" ||
    webosManifest.main !== "index.html" ||
    !webosManifest.id) {
  throw new Error("webOS appinfo.json is invalid");
}

console.log(
  "PS Link Tizen/webOS receiver package smoke tests passed."
);
console.log(
  "Note: these tests do not build TV packages or validate media decoding."
);

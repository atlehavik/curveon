import { layers, namedFlavor } from "@protomaps/basemaps";
import fs from "fs";
const flavor = process.argv[2] || "dark";
const style = {
  version: 8,
  name: `VibeNav ${flavor}`,
  glyphs: "asset://map/fonts/{fontstack}/{range}.pbf",
  sprite: `asset://map/sprites/${flavor}`,
  sources: {
    protomaps: {
      type: "vector",
      url: "pmtiles://__PMTILES_URL__",
      attribution: "© OpenStreetMap contributors, Protomaps"
    }
  },
  layers: layers("protomaps", namedFlavor(flavor), { lang: "nb" })
};
fs.writeFileSync(`style-${flavor}.json`, JSON.stringify(style));
const fonts = new Set();
for (const l of style.layers) { const f = l.layout && l.layout["text-font"]; if (f) f.forEach(x => fonts.add(x)); }
console.log(flavor, style.layers.length, "layers; fonts:", [...fonts].join(", "));

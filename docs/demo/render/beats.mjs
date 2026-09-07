/* Parse docs/demo/script.md into per-scene narration beats.
   The blockquote lines under each "## Scene N" heading are the spoken text;
   a blank quote line separates beats. Scene durations/offsets come from the
   page itself (SCENES[].dur), so the two can never disagree silently. */
import { readFileSync, writeFileSync } from "node:fs";

const md = readFileSync("../script.md", "utf8");
const html = readFileSync("../index.html", "utf8");

// scene durations, read straight out of the composition
const scenes = [...html.matchAll(/num:(\d+), name:"([^"]+)", dur:(\d+)/g)]
  .map(m => ({ num:+m[1], name:m[2], dur:+m[3] }));
let acc = 0;
for (const s of scenes){ s.offset = acc; acc += s.dur; }

// spoken text per scene
const blocks = md.split(/\n## Scene (\d+) — /).slice(1);
const beatsByScene = {};
for (let i = 0; i < blocks.length; i += 2){
  const num = +blocks[i], body = blocks[i+1];
  const quoted = body.split("\n").filter(l => l.startsWith(">")).map(l => l.replace(/^>\s?/, ""));
  const beats = quoted.join("\n").split(/\n\s*\n/).map(t => t.replace(/\s+/g," ").trim()).filter(Boolean);
  beatsByScene[num] = beats;
}

// TTS-friendly text: the em dashes and curly quotes are for the reader, not the voice
const speak = t => t
  .replace(/[‘’]/g, "'").replace(/[“”]/g, '"')
  .replace(/\s*[—–]\s*/g, ", ")
  .replace(/\s*·\s*/g, ", ")
  .replace(/\byour\b/g, "your")
  .replace(/,\s*,/g, ",").replace(/\s+/g, " ").trim();

const out = [];
for (const s of scenes){
  const beats = beatsByScene[s.num] || [];
  if (!beats.length) throw new Error("no narration for scene " + s.num);
  beats.forEach((text, i) => out.push({
    scene: s.num, sceneOffset: s.offset, sceneDur: s.dur, beat: i,
    words: text.split(/\s+/).length, text, speak: speak(text)
  }));
}
writeFileSync("beats.json", JSON.stringify(out, null, 2));

// a words-per-scene sanity check against the authored duration (150 wpm)
console.log("scene  dur  beats  words  est@150wpm");
for (const s of scenes){
  const b = out.filter(x => x.scene === s.num);
  const w = b.reduce((n,x) => n + x.words, 0);
  const est = w / 150 * 60 + (b.length - 1) * 0.35;
  const flag = est > s.dur - 1 ? "  <-- TIGHT" : "";
  console.log(String(s.num).padEnd(6), String(s.dur).padEnd(4), String(b.length).padEnd(6),
              String(w).padEnd(6), est.toFixed(1) + "s" + flag);
}
console.log("total beats", out.length, "· total words", out.reduce((n,x)=>n+x.words,0));

/* 1. lay the 31 narration beats onto one 330s bed at their planned starts
   2. derive caption cues INSIDE each beat by character share of that beat's
      measured audio - the script supplies the text (so technical terms stay
      correct) and the audio supplies the timing. */
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";

const plan = JSON.parse(readFileSync("plan.json", "utf8"));
const TOTAL = Math.max(...plan.map(p => p.sceneOffset + p.sceneDur));

/* ---------- audio bed ---------- */
const args = ["-y"];
plan.forEach(p => args.push("-i", p.file));
const chains = plan.map((p,i) => `[${i}:a]adelay=${Math.round(p.start*1000)}:all=1[a${i}]`);
const mix = plan.map((_,i) => `[a${i}]`).join("");
args.push("-filter_complex",
  `${chains.join(";")};${mix}amix=inputs=${plan.length}:normalize=0:dropout_transition=0[m];` +
  `[m]apad,atrim=0:${TOTAL},loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[out]`,
  "-map", "[out]", "-c:a", "pcm_s16le", "narration.wav");
execFileSync("ffmpeg", args, { stdio: ["ignore","ignore","pipe"] });
console.log("narration.wav built");

/* ---------- cues ---------- */
const MAX_BURN = 72;            // one line, so burned captions never cover the frame
function sentences(t){
  return t.match(/[^.!?]+[.!?]+|\S[^.!?]*$/g)?.map(s => s.trim()).filter(Boolean) ?? [t];
}
/** split a long sentence into balanced cues, breaking at punctuation where one
    is near the middle and at a word boundary otherwise. A subtitle that breaks
    mid-phrase and leaves one word on its own reads worse than a longer line. */
function balance(s, max){
  if (s.length <= max) return [s];
  const mid = s.length / 2;
  const cand = [];
  for (const m of s.matchAll(/[,;:—]\s+/g)) cand.push({ at: m.index + m[0].length, punct: true });
  for (const m of s.matchAll(/\s+/g))       cand.push({ at: m.index + m[0].length, punct: false });
  const usable = cand.filter(c => c.at <= max + 1 && s.length - c.at <= max * 3);
  if (!usable.length) return [s.slice(0, max), ...balance(s.slice(max).trim(), max)];
  usable.sort((a,b) => (Math.abs(a.at-mid) - a.punct*mid*0.25) - (Math.abs(b.at-mid) - b.punct*mid*0.25));
  const at = usable[0].at;
  return [s.slice(0, at).trim(), ...balance(s.slice(at).trim(), max)];
}
function chunk(text, max){
  const out = [];
  for (const s of sentences(text)) out.push(...balance(s, max));
  // never leave a stub on screen by itself
  for (let i = out.length - 1; i > 0; i--){
    if (out[i].length < 24 && out[i-1].length + out[i].length + 1 <= max + 18){
      out[i-1] = out[i-1] + " " + out[i]; out.splice(i,1);
    }
  }
  return out;
}
function cuesFor(p, max){
  const parts = chunk(p.text, max);
  const chars = parts.reduce((n,s) => n + s.length, 0);
  let t = p.start;
  return parts.map(s => {
    const d = p.audioDur * (s.length / chars);
    const c = { start: t, end: t + d, text: s };
    t += d;
    return c;
  });
}
const ts = (s, sep) => {
  const h = Math.floor(s/3600), m = Math.floor(s%3600/60), sec = s%60;
  return `${String(h).padStart(2,"0")}:${String(m).padStart(2,"0")}:` +
         (sep === "," ? sec.toFixed(3).padStart(6,"0").replace(".",",")
                      : sec.toFixed(2).padStart(5,"0"));
};

// sidecar SRT: two lines allowed, for YouTube / <track>
const srt = plan.flatMap(p => cuesFor(p, 88)).map((c,i) =>
  `${i+1}\n${ts(c.start,",")} --> ${ts(c.end,",")}\n` +
  (c.text.length > 46 ? c.text.replace(new RegExp(`^(.{1,46})\\s`), "$1\n") : c.text) + "\n").join("\n");
writeFileSync("captions.srt", srt);

// burned captions: single line, pinned to the free strip at the very bottom.
// One cue list, two consumers - cues.json for the page (CUES=cues.json grab-frames.mjs,
// which draws them itself) and captions.ass for a libass-enabled ffmpeg.
const burn = plan.flatMap(p => cuesFor(p, MAX_BURN));
writeFileSync("cues.json", JSON.stringify(burn, null, 2));
const ass = `[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
Style: fc,Helvetica Neue,30,&H000F172A,&H000F172A,&H00FFFFFF,&HB0FFFFFF,0,0,0,0,100,100,0,0,3,7,0,2,150,470,16,1

[Events]
Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
${burn.map(c => `Dialogue: 0,${ts(c.start,".")},${ts(c.end,".")},fc,,0,0,0,,${c.text.replace(/\n/g," ")}`).join("\n")}
`;
writeFileSync("captions.ass", ass);
console.log(`captions: ${srt.split("\n\n").length-1} sidecar cues · ${burn.length} burned cues` +
            ` → captions.srt, cues.json, captions.ass`);
console.log("longest burned line:", Math.max(...burn.map(c => c.text.length)), "chars");

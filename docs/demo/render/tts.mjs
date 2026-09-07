/* Synthesize every narration beat with the local voice, then FIT each scene:
   the visuals are fixed, so a scene whose narration overruns its authored
   duration is re-synthesized slightly faster until it fits. Nothing is
   guessed from word counts - every number below is measured audio. */
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";

const BEATS = JSON.parse(readFileSync("beats.json", "utf8"));
const VOICE = "am_michael";
const BASE_SPEED = 1.08;      // the local voice runs 140 wpm at 1.0
const LEAD = 0.5, GAP = 0.35, TAIL_MIN = 0.4;
const CLI = "./node_modules/.bin/hyperframes";
mkdirSync("audio", { recursive: true });

const dur = f => Number(execFileSync("ffprobe",
  ["-v","error","-show_entries","format=duration","-of","csv=p=0",f]).toString().trim());

function synth(b, speed, force){
  const f = `audio/s${b.scene}-b${b.beat}.wav`;
  if (!force && existsSync(f)) return { f, d: dur(f), speed };
  execFileSync(CLI, ["tts", b.speak, "--voice", VOICE, "--speed", String(speed), "-o", f],
               { stdio: "ignore" });
  return { f, d: dur(f), speed };
}

const scenes = [...new Set(BEATS.map(b => b.scene))];
const plan = [];
for (const num of scenes){
  const beats = BEATS.filter(b => b.scene === num);
  const { sceneDur, sceneOffset } = beats[0];
  const budget = sceneDur - LEAD - TAIL_MIN - GAP*(beats.length-1);
  let speed = BASE_SPEED, take = beats.map(b => synth(b, speed));
  let spoken = take.reduce((n,x) => n + x.d, 0);
  for (let attempt = 0; spoken > budget && attempt < 3; attempt++){
    speed = Math.min(1.25, Math.round(speed * (spoken/budget) * 100) / 100 + 0.01);
    take = beats.map(b => synth(b, speed, true));
    spoken = take.reduce((n,x) => n + x.d, 0);
  }
  let t = sceneOffset + LEAD;
  beats.forEach((b,i) => {
    plan.push({ ...b, file: take[i].f, audioDur: take[i].d, speed, start: Math.round(t*1000)/1000 });
    t += take[i].d + GAP;
  });
  const used = t - GAP - sceneOffset;
  console.log(`scene ${num}: ${beats.length} beats · speed ${speed.toFixed(2)} · ` +
    `narration ${spoken.toFixed(1)}s in ${sceneDur}s · ends at +${used.toFixed(1)}s` +
    (used > sceneDur ? "   *** OVERRUNS ***" : ` · ${(sceneDur-used).toFixed(1)}s tail`));
}
writeFileSync("plan.json", JSON.stringify(plan, null, 2));
const total = Math.max(...BEATS.map(b => b.sceneOffset + b.sceneDur));
console.log(`\n${plan.length} beats placed · video ${total}s`);

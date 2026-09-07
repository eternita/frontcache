/* Frame grabber for docs/demo/index.html (output B).
   The page is a pure function of time: window.FCDemo.seekGlobal(t) mounts the
   right scene, sets the time and renders synchronously. So a frame is just
   "seek, screenshot" — deterministic, and parallelisable across tabs. */
import puppeteer from "puppeteer-core";
import { mkdirSync, existsSync, readFileSync } from "node:fs";
import path from "node:path";

const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const PAGE   = process.argv[2];
const OUT    = process.argv[3];
const FPS    = Number(process.env.FPS || 30);
const W      = Number(process.env.W || 1920);
const H      = Number(process.env.H || 1080);
const WORKERS= Number(process.env.WORKERS || 4);
const FROM   = Number(process.env.FROM || 0);            // seconds
const TO     = process.env.TO ? Number(process.env.TO) : null;

mkdirSync(OUT, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ["--allow-file-access-from-files", "--font-render-hinting=none",
         "--force-color-profile=srgb", "--hide-scrollbars", "--force-device-scale-factor=1"]
});

async function newPage(){
  const p = await browser.newPage();
  await p.setViewport({ width: W, height: H, deviceScaleFactor: 1 });
  await p.goto("file://" + path.resolve(PAGE) + "?render=1", { waitUntil: "load" });
  await p.waitForFunction("window.FCDemo && window.FCDemo.total > 0");
  await p.evaluate(() => window.FCDemo.pause());
  if (process.env.CUES){                      // burn captions into the frame
    const cues = JSON.parse(readFileSync(process.env.CUES, "utf8"));
    await p.evaluate(c => window.FCDemo.setCaptions(c), cues);
  }
  return p;
}

const probe = await newPage();
const total = TO ?? await probe.evaluate(() => window.FCDemo.total);
const first = Math.round(FROM * FPS), last = Math.round(total * FPS) - 1;
const count = last - first + 1;
console.log(`total ${total}s · ${FPS} fps · frames ${first}..${last} (${count}) · ${W}x${H} · ${WORKERS} workers`);

const slice = Math.ceil(count / WORKERS);
const t0 = Date.now();
let done = 0;

async function worker(i, page){
  const a = first + i*slice, b = Math.min(last, a + slice - 1);
  for (let f = a; f <= b; f++){
    const file = path.join(OUT, String(f).padStart(6,"0") + ".jpg");
    if (existsSync(file)){ done++; continue; }                 // resumable
    await page.evaluate(t => window.FCDemo.seekGlobal(t), f / FPS);
    await page.screenshot({ path: file, type: "jpeg", quality: 92, optimizeForSpeed: true });
    if (++done % 250 === 0){
      const el = (Date.now()-t0)/1000, rate = done/el;
      console.log(`${done}/${count} frames · ${rate.toFixed(1)} fps · eta ${Math.round((count-done)/rate)}s`);
    }
  }
  await page.close();
}

const pages = [probe];
for (let i = 1; i < WORKERS; i++) pages.push(await newPage());
await Promise.all(pages.map((p,i) => worker(i,p)));
await browser.close();
console.log(`done: ${done} frames in ${Math.round((Date.now()-t0)/1000)}s → ${OUT}`);

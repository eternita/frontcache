# `docs/demo/render/` — rendering the narrated MP4 (output B)

The MP4 is **not committed** (it belongs on YouTube and the licensing page, not in a git
repository). This directory is how it is produced, so a re-render is a command rather than a
studio day — which is the whole reason the narration is synthesized
([the plan's §8](../script.md)).

The frame source is [`../index.html`](../index.html) itself, opened with `?render=1`: the page
furniture disappears, the stage goes full-bleed 16:9, and `window.FCDemo.seekGlobal(t)` mounts the
right scene, sets the time and renders **synchronously**. Every scene is a pure function of its
local time, so a frame grabber can step 1/30 s and get a deterministic frame — and the video and
the interactive page can never drift, because they are the same DOM.

## Requirements

- Node 20+, `npm install` in this directory (`puppeteer-core`, `hyperframes`).
- Google Chrome at `/Applications/Google Chrome.app` (edit `CHROME` in `grab-frames.mjs` otherwise).
  `puppeteer-core` drives the installed browser and downloads nothing.
- `ffmpeg` and `ffprobe` on `PATH`.
- The local voice: `pip install kokoro-onnx soundfile`. The first `tts` run downloads
  Kokoro-82M (~340 MB) and its voices (~27 MB). For HeyGen voices instead — better quality, and
  word timestamps — run `npx hyperframes auth login` first and use the `hyperframes-media` skill's
  `heygen-tts.mjs`; `tts.mjs` then only needs its `CLI` line repointed.

## The five steps

[`build.sh`](build.sh) runs all of them, in order, with the encodes at the end — `./build.sh`
for the lot (~20 min cold), or `./build.sh <stage>…` for a subset, where a stage is one of
`beats tts captions frames frames-cap encode`. It preflights the tools listed above and refuses
to encode a frame directory with gaps in it. The steps individually:

```bash
npm install
node beats.mjs                 # script.md + index.html  → beats.json
node tts.mjs                   # beats.json              → audio/*.wav + plan.json
node build-audio-captions.mjs  # plan.json               → narration.wav, cues.json, captions.{srt,ass}
node grab-frames.mjs ../index.html frames                        # 9,900 frames
CUES=cues.json node grab-frames.mjs ../index.html frames-cap     # the captioned pass
```

Then encode (see `ffmpeg` lines below).

1. **`beats.mjs`** — parses the blockquoted narration out of [`../script.md`](../script.md) into
   beats (a blank quote line separates them) and reads the scene durations **out of
   `index.html` itself** (`SCENES[].dur`), so the script and the composition cannot disagree
   silently. Prints a words-vs-duration table.
2. **`tts.mjs`** — synthesizes every beat and then **fits each scene**: the visuals are fixed, so
   if a scene's measured narration does not fit its authored duration, that scene is
   re-synthesized slightly faster (capped at 1.25×) until it does. Nothing is inferred from word
   counts. Writes `plan.json` — per beat: file, measured duration, speed, and global start.
   The local voice measures **140 wpm at speed 1.0**, hence the 1.08 base.
3. **`build-audio-captions.mjs`** — lays the beats onto one bed at their planned starts
   (`adelay` + `amix`, then `loudnorm` to −16 LUFS), and derives caption cues *inside* each beat by
   character share of that beat's measured audio: the **text** comes from the script (so
   `fc:include`, `429` and `L1` stay correct) and the **timing** comes from the audio. Emits a
   two-line `captions.srt` sidecar, plus one single-line cue list in two encodings for the two
   burn-in routes: `cues.json` for the page (step 4) and `captions.ass` for `libass`.
4. **`grab-frames.mjs`** — seek, screenshot, repeat, across 4 tabs on contiguous ranges (~16 fps,
   so ~10 min for 5:30). Resumable: existing frames are skipped, so a re-run after changing one
   scene only needs that scene's range deleted. `FROM` / `TO` (seconds), `FPS`, `W`, `H`,
   `WORKERS` are env vars. With `CUES=cues.json` (written by step 3) it calls
   `FCDemo.setCaptions()` and the captions are rendered **by the page**, styled by the page's own
   stylesheet and positioned clear of the counter panel.
5. **Encode.**

```bash
# master: 1920x1080, 30 fps, narration, sidecar SRT alongside
ffmpeg -y -framerate 30 -i frames/%06d.jpg -i narration.wav -map 0:v -map 1:a \
  -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -profile:v high -level 4.1 -g 60 \
  -c:a aac -b:a 192k -ac 2 -ar 48000 -shortest -movflags +faststart out/frontcache-intro.mp4

# captions-burned cut, for silent autoplay feeds
ffmpeg -y -framerate 30 -i frames-cap/%06d.jpg -i narration.wav -map 0:v -map 1:a \
  -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -profile:v high -level 4.1 -g 60 \
  -c:a aac -b:a 192k -ac 2 -ar 48000 -shortest -movflags +faststart out/frontcache-intro-captions.mp4
```

**Why the captions are drawn by the page and not by `ffmpeg`.** Homebrew's default `ffmpeg` is
built without `libass`, so `-vf ass=…` / `subtitles=…` simply do not exist in it — and burning a
caption band over a schematic covers the frame's own bottom-left content anyway. Rendering them in
the composition solves both: same fonts, same palette, and margins that keep them out of the
counter panel. `captions.ass` is kept for anyone with a `libass` build who wants the filter route.

## Re-rendering after a change

- **A wording change** → `beats.mjs`, `tts.mjs`, `build-audio-captions.mjs`, re-encode. No
  `frames/` — the visuals are a pure function of local time and the scene durations did not
  move. `frames-cap/` *does* have to go: the new `cues.json` is baked into those frames.
- **A visual change inside one scene** → delete that scene's frame range and re-run
  `grab-frames.mjs` (it resumes), then re-encode. Frame index = `global seconds × 30`.
- **A scene duration change** → everything after it shifts; delete from that scene's first frame
  onward. Re-run `tts.mjs` too: the fit is per scene.
- **Scene 5 landing** → it is a new `SCENES` entry, so both passes re-render from scene 5 onward
  and every later scene's frame indices shift. Budget the full ~20 minutes.

## Social cuts

Per-scene extracts, straight off the master — the offsets are in `plan.json`:

```bash
ffmpeg -y -ss 180 -t 45 -i out/frontcache-intro.mp4 -c copy out/scene-6-guard.mp4
```

For 9:16 or 1:1 the page has to be re-grabbed at that size (`W=1080 H=1920`), and the scenes are
authored for 16:9 — treat a vertical cut as a design task, not a crop.

# `docs/demo/` — the interactive walkthrough

[**index.html**](index.html) is the 7-scene interactive explainer: what Frontcache does to a
request, why the second hit is different from the first, what it costs the origin, and where it
goes in your infrastructure.

**To view it:** open `index.html` in a browser. That is the whole procedure — no server, no build
step, no network. One file, no dependencies, works in a fresh clone and works offline.

GitHub renders an `.html` file as source rather than as a page, so clicking it here will not run
it: clone the repository (or download the one file) and open it locally. The same file is what a
GitHub Pages site or any static host would serve, unchanged.

| | |
|---|---|
| Scenes | 7, each self-contained, 35–55 s · **5:30** end to end |
| Interactive | scenes 2, 3 and 4 — the viewer drives the state and the counters answer |
| Narration script | [script.md](script.md) — the source of truth for every spoken line and every number |
| Plan it implements | `docs/intro-vidio-plan.md` in the development repository |

Keyboard: <kbd>space</kbd> play/pause, <kbd>←</kbd>/<kbd>→</kbd> ±2 s, <kbd>1</kbd>…<kbd>7</kbd>
jump to a scene. `index.html#scene-3` deep-links a scene, which is what the README table and the
social cuts link to.

## Why one HTML file

Two outputs come from this one source:

- **A — the interactive page** (this file): the viewer scrubs the timeline or drives the state.
- **B — a narrated MP4**: the same DOM and the same animation definitions, driven by a render
  timeline instead of by clicks.

Building the schematics twice is the failure mode this avoids: a fix to a diagram must land in
both, so there is only one place to fix.

## How it is built

No framework, no CDN, no build. Inline CSS, inline JS, hand-authored SVG.

**The one rule that matters: every scene is a pure function of `(local time, driven state)`.**
`render(t)` may be called for any `t`, in any order, with no memory of the frame before it. Nothing
animates by mutating state over time — that is what makes the timeline scrubbable, makes a scene
seek-safe for a deterministic video render, and keeps the two outputs honest about being the same
thing.

```js
SCENES.push({
  num: 3, name: "1st hit, 2nd hit, N hits", dur: 55, interactive: true,
  lands: "…the one sentence the scene must land…",
  sources: [["link text", "../value.md"], …],   // every scene ends in a doc
  build(root){
    // draw the SVG once, keep references
    return {
      render(t, driving){ /* set attributes from t */ },
      counters(t, driving){ /* the panel bottom-right */ },
      controls(host, refresh){ /* interactive scenes only */ },
      readout(){ /* the sentence under the buttons */ }
    };
  }
});
```

Shared vocabulary, established in scene 1 and never redefined (plan §3):

- **`LANES`** — Client · Guard · Cache · Include processor · Origin · Backend, at fixed `x`
  positions. A scene draws a subset. **A scene never moves a lane.**
- **`dispo(kind)`** — colour *is* disposition, in the words Frontcache writes in its own logs:
  `cached` → green `from-cache`, `dynamic` → amber, `error` → red `error`/`fallback`, `refused` →
  grey `rejected`/`redirected`, plus `l2` (teal) and `engine` (indigo) for structure. Do not use a
  disposition colour for decoration; the colours are load-bearing.
- **`box` / `codePanel` / `legend` / `laneFrame`** — the drawing primitives. Reuse them rather than
  hand-rolling a rect, so the geometry stays the geometry of
  [`docs/diagrams/`](../diagrams) — the diagrams a viewer lands on next.

**One non-obvious trap, since it silently swallowed every colour once already:** a `fill="…"`
*attribute* on an SVG `<text>` loses to any stylesheet rule (`svg text{fill:…}`, `.sm`, `.xs`), so
coloured text renders in the default ink. `el()` therefore routes a text `fill` into an inline
style. Keep passing `fill:` as an attribute — the helper handles it — and do not add `fill` to a
CSS text rule expecting an attribute to win.

## Editing rules

1. **No number that is not traceable.** Every figure on screen is sourced in
   [script.md](script.md)'s table, with the caveat it has to carry. There is no "typical customer
   sees X" number and there is not going to be one ([value.md §6](../value.md)).
2. **The script changes first.** A claim gets checked against the numbers policy in
   [script.md](script.md), then animated — not the other way round.
3. **Disagreement with [`docs/diagrams/`](../diagrams) is a bug in one of them.** Fix whichever is
   wrong; do not let the video and the docs drift.
4. **Generic on purpose.** `example.com`, a storefront, no named deployment, no live-site volumes.
   The named case study is the follow-up video's job.
5. Every scene ends with a link to the doc that does the job properly. This is not a tutorial.

## Scene 5 is missing on purpose

The storyboard has eight scenes. **Scene 5 — *combine &amp; reduce*, N fragments in one origin
call — is held until there is a measured number for it**, because a number is that scene's entire
payoff and the modelled arithmetic is the weakest version of the strongest feature. The feature
itself is fully documented: [include-combining.md](../include-combining.md).

The surviving seven keep their storyboard numbers (…4, **6**, 7, 8), so scene 5 drops in without
renumbering, re-narrating or re-cutting anything around it. To land it:

1. add the measurement and its caveat to [script.md](script.md)'s numbers table;
2. `SCENES.splice(4, 0, {num:5, …})` — the array order is the timeline order;
3. update the counts in `index.html` (the header's "7 scenes · 5:30", the clock and the footer note) and the
   scene-5 note in this file.

Nothing else needs to change: the chapter strip, the clock, the deep links and the
`window.FCDemo` timeline are all derived from `SCENES`.

## The narrated MP4 (output B)

**Rendered.** 1920×1080, 30 fps, **5:30**, synthesized narration, plus a captions-burned cut and a
sidecar SRT. The MP4 is deliberately **not committed** — it belongs on YouTube and the licensing
page, not in a git repository. [`render/`](render/) is the pipeline that produces it, and
[`render/README.md`](render/README.md) is the five commands.

The frames come from **this page**, opened with `?render=1`: the page furniture disappears, the
stage goes full-bleed 16:9, and the grabber steps `FCDemo.seekGlobal(t)` 1/30 s at a time. So the
video is not a second production — it is the same DOM, driven by a render timeline instead of by
clicks, which is what §2 of the plan asks for.

```js
window.FCDemo.total            // 330 seconds
window.FCDemo.scenes           // [{index, num, name, dur, offset}, …]
window.FCDemo.seek(index, t)   // one scene, local time
window.FCDemo.seekGlobal(gt)   // 0 … total, across scene boundaries
window.FCDemo.setCaptions([…]) // burn-in captions: [{start, end, text}], global seconds
```

Two things worth knowing before you re-render:

- **The narration is fitted to the visuals, not the reverse.** `render/tts.mjs` measures the
  synthesized audio and re-synthesizes a scene slightly faster if it overruns the duration
  authored in `index.html`. A word count is not evidence; the audio is. The first pass of this
  script overran every scene by ~40% and had to be cut back — if you edit
  [script.md](script.md), run the pipeline's first three steps before believing it fits.
- **Captions are drawn by the composition**, via `setCaptions()`, not burned by `ffmpeg` — the
  usual Homebrew `ffmpeg` has no `libass`, and a caption band over a schematic covers the frame's
  own content. Drawing them here keeps the page's fonts and palette and keeps them clear of the
  counter panel.

Still outstanding for the video: the **two cutaways** the plan calls for — ~2 s of the console
realtime monitor in scene 3, and a dashboard in scene 8. Both need the redaction pass first: no
api key, no hostnames, no client IPs, no internal origin names, no identifying page content. A
cutaway is there to prove these states are observable, not to show whose traffic they are. And the
**social cuts** are per-scene extracts off the master (`render/README.md` has the one-liner);
scene 6 travels furthest and should go out first.

## Review gates

Before either output ships:

1. **Script vs numbers policy** — every figure traceable, every caveat present ([script.md](script.md)).
2. **Schematics vs [`docs/diagrams/`](../diagrams)** — any disagreement is a bug in one of them.
3. **Screenshot redaction** — for the two cutaways, per the list above.

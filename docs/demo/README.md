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

Keyboard: <kbd>space</kbd> play/pause, <kbd>←</kbd>/<kbd>→</kbd> ±2 s, <kbd>1</kbd>…<kbd>7</kbd>
jump to a scene. `index.html#scene-3` deep-links a scene.

There is also a **narrated MP4** of the same seven scenes — the same DOM, driven by a render
timeline instead of by clicks. It is deliberately not committed: it belongs on YouTube and the
licensing page, not in a git repository.

**Everything else about this demo lives in the Frontcache product source repository**, under
`tools/` — the narration script (`script.md`, the source of truth for every spoken line and every
number), the design notes for the composition, and the two pipelines that render video from it:
`tools/render/` for this page, and `tools/revoice/` for re-voicing a screen recording. They read
`index.html` from here; nothing writes back to it.

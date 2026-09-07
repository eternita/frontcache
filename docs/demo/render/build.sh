#!/usr/bin/env bash
# Runs the whole render pipeline from README.md: script + composition -> the two MP4s.
#
#   ./build.sh                    # everything, ~20 min from cold
#   ./build.sh captions           # one stage
#   ./build.sh frames encode      # a few, in the order given
#
# Stages: beats tts captions frames frames-cap encode
#
# grab-frames.mjs env vars pass straight through, so a partial re-render is
# e.g.  FROM=180 TO=225 ./build.sh frames  (see "Re-rendering after a change").
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

ALL=(beats tts captions frames frames-cap encode)
STAGES=("${@:-}")
[[ -z "${STAGES[*]}" ]] && STAGES=("${ALL[@]}")

for s in "${STAGES[@]}"; do
  [[ " ${ALL[*]} " == *" $s "* ]] || { echo "unknown stage: $s"; echo "stages: ${ALL[*]}"; exit 2; }
done

CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"   # keep in sync with grab-frames.mjs
t0=$SECONDS
say(){ printf '\n\033[1m[%02d:%02d] %s\033[0m\n' $(((SECONDS-t0)/60)) $(((SECONDS-t0)%60)) "$*"; }
want(){ [[ " ${STAGES[*]} " == *" $1 "* ]]; }

# ---------- preflight ----------
for bin in node npm ffmpeg ffprobe; do
  command -v "$bin" >/dev/null || { echo "missing: $bin (see README Requirements)"; exit 1; }
done
if want frames || want frames-cap; then
  [[ -x "$CHROME" ]] || { echo "missing: Google Chrome at $CHROME"; echo "edit CHROME in build.sh and grab-frames.mjs"; exit 1; }
fi
[[ -d node_modules ]] || { say "npm install"; npm install; }
if want tts && ! python3 -c "import kokoro_onnx" 2>/dev/null; then
  echo "note: kokoro-onnx not importable — 'pip install kokoro-onnx soundfile' if tts fails."
fi

# frames/%06d.jpg stops at the first gap, silently. Catch that before encoding.
check_frames(){
  local dir=$1 n max
  [[ -d $dir ]] && n=$(find "$dir" -name '*.jpg' | wc -l | tr -d ' ') || n=0
  [[ $n -gt 0 ]] || { echo "no frames in $dir/ — run the matching frames stage first"; exit 1; }
  max=$(basename "$(find "$dir" -name '*.jpg' | sort | tail -1)" .jpg)
  [[ -f "$dir/000000.jpg" ]] || { echo "$dir/ does not start at 000000.jpg"; exit 1; }
  [[ $n -eq $((10#$max + 1)) ]] || { echo "$dir/ has gaps: $n files but highest index is $max — re-run the frames stage (it resumes)"; exit 1; }
  echo "$dir: $n frames, contiguous"
}

encode(){ # <frames dir> <output>
  ffmpeg -y -loglevel error -stats -framerate 30 -i "$1/%06d.jpg" -i narration.wav -map 0:v -map 1:a \
    -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -profile:v high -level 4.1 -g 60 \
    -c:a aac -b:a 192k -ac 2 -ar 48000 -shortest -movflags +faststart "$2"
}

# ---------- the five steps ----------
if want beats;      then say "1/5 beats.mjs — script.md + index.html → beats.json"; node beats.mjs; fi
if want tts;        then say "2/5 tts.mjs — synthesize and fit each scene";        node tts.mjs;   fi
if want captions;   then say "3/5 build-audio-captions.mjs — narration.wav, cues.json, captions.{srt,ass}"
                         node build-audio-captions.mjs; fi
if want frames;     then say "4/5 grab-frames.mjs — the master pass"
                         node grab-frames.mjs ../index.html frames; fi
if want frames-cap; then say "4/5 grab-frames.mjs — the captioned pass"
                         CUES=cues.json node grab-frames.mjs ../index.html frames-cap; fi

if want encode; then
  say "5/5 encode"
  [[ -f narration.wav ]] || { echo "no narration.wav — run the captions stage first"; exit 1; }
  mkdir -p out
  check_frames frames;     encode frames     out/frontcache-intro.mp4
  check_frames frames-cap; encode frames-cap out/frontcache-intro-captions.mp4
fi

say "done"
if want encode; then ls -lh out/*.mp4 | awk '{print "  " $5, $9}'; fi

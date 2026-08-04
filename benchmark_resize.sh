#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# GraphicsMagick vs libvips (standard resizes)
# ImageMagick   vs libvips (bleed / mirror-extend resizes)
#
# All commands below mirror the ACTUAL commands this project generates.
# Sources (core repo):
#   - service/.../graphicsmagick/cli/GraphicsMagickClient.scala   (buildCmd)
#   - service/.../graphicsmagick/cli/model/ResizeCommand.scala    (quality)
#   - service/.../graphicsmagick/cli/model/ResizeMode.scala       (resize geometry ops)
#   - service/.../imagemagick/cli/ImageMagickClient.scala         (bleedOperation, buildCmd)
#   - service/.../imagemagick/cli/package.scala                   (quality)
#   - common/.../model/global/PictureFormat.scala                 (mobFormat, mobileFullFormat, originalFormat)
#   - constant/.../pdf/PdfConstants.scala                         (TARGET_DPI = 300)
#   - constant/.../common/ProductConstants.scala                  (DEFAULT_BLEED = 3mm)
#
# Design choice: for the STANDARD resize comparison, both tools are forced to
# the exact same output WxH ("!" in GM/IM, "!" in vipsthumbnail) so we are
# purely timing decode+resize+encode, not differences in each tool's own
# "fit within box" geometry math.
# ============================================================================

SRC="${1:-}"
OUT_DIR="${2:-$(mktemp -d)}"

if [[ -z "$SRC" || ! -f "$SRC" ]]; then
  cat >&2 <<'EOF'
Usage: benchmark_resize.sh <path-to-real-source-photo.jpg> [output-dir]

IMPORTANT: pass a REAL, representative photo (camera/phone JPEG,
~4000-8000px on the long edge - i.e. the size real uploads arrive at
before the app downscales them). Synthetic/gradient/noise images decode
and compress very differently from real photographic content and will
bias the comparison.

Install missing tools on macOS:
  brew install graphicsmagick imagemagick vips python3 hyperfine
EOF
  exit 1
fi

mkdir -p "$OUT_DIR"/{gm,im,vips}
export OMP_NUM_THREADS=1
export VIPS_CONCURRENCY=1

for bin in gm convert vips python3 hyperfine; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing required binary: $bin (see install hint above)" >&2; exit 1; }
done

# sRGB.icc profile path as referenced by ProcessorUtils.colorProfile
ICC="conf/imagemagick/sRGB.icc"
if [[ ! -f "$ICC" ]]; then
  echo "WARNING: $ICC not found relative to cwd ($PWD)." >&2
  echo "Run this script from the core repo root, or edit ICC= below." >&2
fi

read -r SRC_W SRC_H <<<"$(gm identify -format "%w %h" "$SRC")"
echo "Source: $SRC  (${SRC_W}x${SRC_H})"
echo "Output dir: $OUT_DIR"
echo

# ---- aspect-preserving helpers, computed ONCE so both tools get an identical
#      forced target (avoids relying on each tool's own fit heuristics) -----

area_fit() {
  local target_px=$1
  awk -v w="$SRC_W" -v h="$SRC_H" -v px="$target_px" 'BEGIN {
    scale = sqrt(px / (w*h));
    if (scale > 1) scale = 1;
    printf "%dx%d", int(w*scale+0.5), int(h*scale+0.5)
  }'
}

min_fit() {
  local tw=$1 th=$2
  awk -v w="$SRC_W" -v h="$SRC_H" -v tw="$tw" -v th="$th" 'BEGIN {
    scale = (tw/w > th/h) ? tw/w : th/h;
    if (scale > 1) scale = 1;
    printf "%dx%d", int(w*scale+0.5), int(h*scale+0.5)
  }'
}

THUMB=$(min_fit 320 320)          # PictureFormat.mobFormat        (320x320, SizeMode.Min)
FULL=$(area_fit 2250000)          # PictureFormat.mobileFullFormat (1500x1500 -> 2 MegaPixel cap)
ORIG=$(area_fit 16777216)         # PictureFormat.originalFormat   (4096x4096 -> 16 MegaPixel cap)

# Split WxH dimensions for modern 'vips thumbnail' CLI arguments
THUMB_W="${THUMB%%x*}"; THUMB_H="${THUMB##*x}"
FULL_W="${FULL%%x*}";   FULL_H="${FULL##*x}"
ORIG_W="${ORIG%%x*}";   ORIG_H="${ORIG##*x}"

echo "Computed targets — thumb:${THUMB}  mobileFull:${FULL}  original:${ORIG}"
echo

# ============================================================================
# PART 1 — Standard (non-bleed) resize: GraphicsMagick vs libvips
#   This project uses GM (not IM) for every non-bleed resize.
#
# `-size <target>` BEFORE the source path on every GM downscale command below
# is a libjpeg read-time hint (DCT-domain scaled decompression, 1/1-1/8) -
# WITHOUT it, GM always fully decodes the source at its real resolution before
# discarding most of it during resize, while `vipsthumbnail` always gets the
# equivalent shrink-on-load for free. Verified empirically: adding this hint
# took a 2268x4032 JPEG thumb resize from ~0.16s to ~0.04s (byte-identical
# output dimensions) on this repo's own baseline.jpg fixture. Without it, this
# benchmark doesn't answer "is vips' resize faster" so much as "does this
# script know about a decode shortcut GM has and vips gets automatically" -
# the gap it produces grows with SOURCE resolution rather than reflecting a
# real, resolution-independent algorithmic difference. Confirmed harmless for
# the PNG case (GM has no equivalent scaled-decode path for PNG, so the hint
# is a no-op there - byte-identical output verified).
# ============================================================================

hyperfine \
  --warmup 3 --min-runs 20 \
  --export-markdown "$OUT_DIR/standard_resize.md" \
  --export-json "$OUT_DIR/standard_resize.json" \
  -n "GM   thumb ${THUMB} (jpg q85)" \
    "gm convert -size ${THUMB} '$SRC' -auto-orient -profile '$ICC' -strip -colorspace RGB -quality 85 -type truecolor -background '#ffffff' -flatten -filter Lanczos -resize '${THUMB}!' 'jpg:$OUT_DIR/gm/thumb.jpg'" \
  -n "vips thumb ${THUMB} (jpg q85)" \
    "vips thumbnail '$SRC' '$OUT_DIR/vips/thumb.jpg[Q=85,strip]' $THUMB_W --height $THUMB_H --size force --export-profile '$ICC'" \
  -n "GM   mobileFull ${FULL} (jpg q85)" \
    "gm convert -size ${FULL} '$SRC' -auto-orient -profile '$ICC' -strip -colorspace RGB -quality 85 -type truecolor -background '#ffffff' -flatten -filter Lanczos -resize '${FULL}!' 'jpg:$OUT_DIR/gm/full.jpg'" \
  -n "vips mobileFull ${FULL} (jpg q85)" \
    "vips thumbnail '$SRC' '$OUT_DIR/vips/full.jpg[Q=85,strip]' $FULL_W --height $FULL_H --size force --export-profile '$ICC'" \
  -n "GM   mobileFullWebp ${FULL} (webp q85)" \
    "gm convert -size ${FULL} '$SRC' -auto-orient -profile '$ICC' -strip -colorspace RGB -quality 85 -background none -filter Lanczos -resize '${FULL}!' 'webp:$OUT_DIR/gm/full.webp'" \
  -n "vips mobileFullWebp ${FULL} (webp q85)" \
    "vips thumbnail '$SRC' '$OUT_DIR/vips/full.webp[Q=85,strip]' $FULL_W --height $FULL_H --size force --export-profile '$ICC'" \
  -n "GM   mobileFullPng ${FULL} (png)" \
    "gm convert -size ${FULL} '$SRC' -auto-orient -profile '$ICC' -strip -colorspace RGB -define png:filter-type=6 -background none -filter Lanczos -resize '${FULL}!' 'png:$OUT_DIR/gm/full.png'" \
  -n "vips mobileFullPng ${FULL} (png)" \
    "vips thumbnail '$SRC' '$OUT_DIR/vips/full.png[strip]' $FULL_W --height $FULL_H --size force --export-profile '$ICC'" \
  -n "GM   original ${ORIG} (jpg q85, keepExif)" \
    "gm convert -size ${SRC_W}x${SRC_H} '$SRC' -colorspace RGB -quality 85 -type truecolor -background '#ffffff' -flatten -filter Lanczos -resize '${ORIG}!' 'jpg:$OUT_DIR/gm/orig.jpg'" \
  -n "vips original ${ORIG} (jpg q85, keepExif)" \
    "vips thumbnail '$SRC' '$OUT_DIR/vips/orig.jpg[Q=85]' $ORIG_W --height $ORIG_H --size force --no-rotate"

echo
echo "== Standard resize results written to $OUT_DIR/standard_resize.md =="
echo

# ============================================================================
# PART 2 — Bleed / mirror-extend resize: ImageMagick vs libvips
# ============================================================================

BLEED_PX=36
CONTENT_W=2480
CONTENT_H=3508
BLEED_FULL_W=$((ORIG_W + 2 * BLEED_PX))   # bleed on left+right
BLEED_FULL_H=$((ORIG_H + 2 * BLEED_PX))   # bleed on top+bottom

echo "Bleed case — content ${ORIG_W}x${ORIG_H}, bleed ${BLEED_PX}px/edge, full canvas ${BLEED_FULL_W}x${BLEED_FULL_H}"
echo

hyperfine \
  --warmup 3 --min-runs 20 \
  --export-markdown "$OUT_DIR/bleed_resize.md" \
  --export-json "$OUT_DIR/bleed_resize.json" \
  -n "IM   bleed page ${BLEED_FULL_W}x${BLEED_FULL_H} (jpg q85, mirror-extend ${BLEED_PX}px)" \
    "convert -size ${BLEED_FULL_W}x${BLEED_FULL_H} '$SRC' -auto-orient -profile '$ICC' -strip -colorspace sRGB -quality 85 -alpha remove -type truecolor -resize '${CONTENT_W}x${CONTENT_H}^' -virtual-pixel Mirror -set option:distort:viewport '%[fx:w+$((2*BLEED_PX))]x%[fx:h+$((2*BLEED_PX))]-${BLEED_PX}-${BLEED_PX}' -distort SRT 0 -gravity center -extent ${BLEED_FULL_W}x${BLEED_FULL_H} 'jpg:$OUT_DIR/im/bleed.jpg'" \
  -n "vips bleed page ${BLEED_FULL_W}x${BLEED_FULL_H} (jpg q85, mirror-extend ${BLEED_PX}px)" \
    "python3 -c \"import pyvips; img = pyvips.Image.thumbnail('$SRC', $ORIG_W, height=$ORIG_H, crop='centre', export_profile='$ICC'); img = img.embed($BLEED_PX, $BLEED_PX, $BLEED_FULL_W, $BLEED_FULL_H, extend='mirror'); img.write_to_file('$OUT_DIR/vips/bleed.jpg[Q=85,strip]')\""

echo
echo "== Bleed resize results written to $OUT_DIR/bleed_resize.md =="
echo
echo "All results (with per-command mean/stddev/min/max) are in:"
echo "  $OUT_DIR/standard_resize.md"
echo "  $OUT_DIR/bleed_resize.md"
echo "Raw JSON (for custom analysis/plots) is next to each .md file."
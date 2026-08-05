#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# GraphicsMagick vs libvips — METADATA EXTRACTION ONLY (no resizing).
#
# Mirrors the two `gm identify` calls this project makes to read basic image
# info and EXIF/GPS data:
#   1. basic:     %w;%h;%[EXIF:Orientation];%m
#   2. full+GPS:  %w;%h;%m;%[EXIF:Orientation];%[EXIF:Make];%[EXIF:Model];
#                 %[EXIF:GPSLongitude];%[EXIF:GPSLongitudeRef];
#                 %[EXIF:GPSLatitude];%[EXIF:GPSLatitudeRef]
#
# WHY ONLY ONE libvips ENTRY FOR BOTH GM CALLS:
# `vipsheader` prints exactly one field per invocation with `-f <name>`, so
# reproducing either GM call field-by-field would mean 4-10 separate processes
# - which would measure "how many times did we start vipsheader", not metadata
# cost. `vipsheader -a` dumps every field (width, height, orientation,
# vips-loader, and all exif-ifd*-* entries incl. Make/Model/GPS*) in ONE call,
# so it is the closest single-invocation equivalent and satisfies BOTH GM
# commands at once. Verified `-a` costs no more than plain `vipsheader`
# (both ~0.07s here - see the startup caveat below, which is why).
#
# ############################################################################
# # READ THIS BEFORE INTERPRETING THE NUMBERS
# #
# # This benchmark is dominated by PROCESS STARTUP, not by metadata work.
# # Measured on this repo's own fixtures:
# #
# #     vipsheader -a   507-byte file : 0.07s     12MB PNG : 0.07s
# #     gm identify     507-byte file : ~0.00s    12MB PNG : ~0.00s
# #
# # vipsheader's time is FLAT across a ~24,000x file-size range - i.e. it is
# # ~100% the cost of exec'ing the binary and loading libvips + glib + its
# # plugin modules, and ~0% the cost of actually parsing the header. GM's
# # binary starts far faster and its header parse is also near-free.
# #
# # So: this script honestly measures "cost of shelling out to each CLI to
# # read metadata" - which IS the real cost if production shells out per
# # image, as the current GM integration does. It does NOT measure "is
# # libvips slower at parsing metadata" - that question is invisible here
# # because neither tool spends meaningful time on it.
# #
# # If the target architecture calls libvips IN-PROCESS (vips-ffm in a
# # long-lived JVM, as the S3 benchmark in this repo does), the ~70ms startup
# # is paid ONCE at boot, not per image, and these numbers do not transfer at
# # all. Do not quote this benchmark as a reason not to migrate without also
# # measuring the in-process path.
# ############################################################################
# ============================================================================

SRC="${1:-}"
OUT_DIR="${2:-$(mktemp -d)}"

if [[ -z "$SRC" || ! -f "$SRC" ]]; then
  cat >&2 <<'EOF'
Usage: benchmark_metadata.sh <path-to-real-source-photo> [output-dir]

IMPORTANT: pass a REAL photo that actually CARRIES EXIF (straight off a
phone/camera, not re-encoded or -strip'ed). A file with no EXIF makes both
tools return empty fields and turns this into a pure "who starts faster"
race with none of the EXIF/GPS parsing the comparison is meant to cover.
The sanity check printed below shows exactly what each tool found, so you
can confirm the fixture actually exercises the EXIF path.

Install missing tools on macOS:
  brew install graphicsmagick vips hyperfine
EOF
  exit 1
fi

# Grouped under a format-named subdir (results/jpg, results/png, ...) so runs
# against different source formats into the SAME output dir don't clobber each
# other - same convention as benchmark_resize.sh.
SRC_EXT="${SRC##*.}"
SRC_FORMAT="$(printf '%s' "$SRC_EXT" | tr '[:upper:]' '[:lower:]')"
FORMAT_DIR="$OUT_DIR/$SRC_FORMAT"

mkdir -p "$FORMAT_DIR"
export OMP_NUM_THREADS=1
export VIPS_CONCURRENCY=1

# Prepends the source filename (+ a blank line) to a hyperfine result file.
# NOTE for the .json file: this makes it no longer strictly valid JSON -
# strip the first two lines before parsing, e.g. `tail -n +3 file.json | jq .`
prepend_source_name() {
  local file=$1
  [[ -f "$file" ]] || return 0
  { printf '%s\n\n' "$SRC"; cat "$file"; } > "${file}.tmp" && mv "${file}.tmp" "$file"
}

for bin in gm vipsheader hyperfine; do
  command -v "$bin" >/dev/null 2>&1 || { echo "Missing required binary: $bin (see install hint above)" >&2; exit 1; }
done

GM_BASIC_FMT='%w;%h;%[EXIF:Orientation];%m'
GM_FULL_FMT='%w;%h;%m;%[EXIF:Orientation];%[EXIF:Make];%[EXIF:Model];%[EXIF:GPSLongitude];%[EXIF:GPSLongitudeRef];%[EXIF:GPSLatitude];%[EXIF:GPSLatitudeRef]'

echo "Source: $SRC"
echo "Output dir: $FORMAT_DIR"
echo

# ---- sanity check: show what each tool actually returns, so it's obvious
#      whether this fixture really exercises the EXIF/GPS path at all -------
echo "--- GM basic  -> $GM_BASIC_FMT"
gm identify -format "$GM_BASIC_FMT" "$SRC" || true
echo
echo "--- GM full   -> (w;h;format;orientation;make;model;GPSLon;GPSLonRef;GPSLat;GPSLatRef)"
gm identify -format "$GM_FULL_FMT" "$SRC" || true
echo
echo "--- vipsheader -a (filtered to the equivalent fields; the real dump has ~everything else too)"
vipsheader -a "$SRC" 2>/dev/null \
  | grep -iE '^(width|height|orientation|vips-loader):|^exif-ifd[0-9]+-(Orientation|Make|Model|GPSLongitude|GPSLongitudeRef|GPSLatitude|GPSLatitudeRef):' \
  || echo "  (none of the expected fields found - fixture may carry no EXIF)"
echo
echo "NOTE: the two tools do NOT emit interchangeable strings. GM returns one"
echo "ready-to-consume delimited line; vipsheader -a returns a labelled dump a"
echo "caller still has to parse - and the values differ in shape (GPS rationals"
echo "are space-separated '16/1 22/1 2257/100' vs GM's comma-separated, and the"
echo "format field is 'jpegload' vs GM's 'JPEG'). That parsing work is real"
echo "migration effort this timing comparison does not capture."
echo

# ============================================================================
# One hyperfine run with all three commands, so they are ranked directly
# against each other - the single vipsheader entry is the libvips answer to
# BOTH GM calls (see the header comment for why there is only one).
# ============================================================================

hyperfine \
  --warmup 3 --min-runs 20 \
  --export-markdown "$FORMAT_DIR/metadata_extract.md" \
  --export-json "$FORMAT_DIR/metadata_extract.json" \
  -n "GM   identify basic (w;h;orientation;format)" \
    "gm identify -format '$GM_BASIC_FMT' '$SRC'" \
  -n "GM   identify full+GPS (w;h;format;orientation;make;model;GPS*)" \
    "gm identify -format '$GM_FULL_FMT' '$SRC'" \
  -n "vips vipsheader -a (all fields; covers both GM calls)" \
    "vipsheader -a '$SRC'"

prepend_source_name "$FORMAT_DIR/metadata_extract.md"

echo
echo "== Metadata extraction results written to $FORMAT_DIR/metadata_extract.md =="
echo
echo "Raw JSON is next to it - note the prepended source-filename line makes it"
echo "non-strict JSON; strip it first:"
echo "  tail -n +3 $FORMAT_DIR/metadata_extract.json | jq ."
echo
echo "REMINDER: these numbers are ~entirely CLI process-startup cost (vipsheader"
echo "measures the same ~0.07s on a 507-byte file as on a 12MB one). Valid for"
echo "'what does shelling out per image cost', NOT for 'is libvips slower at"
echo "reading metadata'. See the header comment in this script before quoting."

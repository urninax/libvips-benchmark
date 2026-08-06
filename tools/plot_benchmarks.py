#!/usr/bin/env python3
"""
Render benchmark result JSON into charts.

Reads the JSON exported by EITHER harness and derives everything from the data -
no hardcoded numbers, so a chart can never silently drift from the run it claims
to show:

  * bench.jar local-resize   -> standard_resize_ffm.json / bleed_resize_ffm.json
                                (strict JSON, carries engine/rss/output_bytes)
  * benchmark_resize.sh      -> standard_resize.json / bleed_resize.json
                                (hyperfine's export; the script prepends the source
                                 filename + blank line, so it is NOT strict JSON -
                                 handled below)

Charts are only emitted when the underlying metric is actually present and
actually decision-relevant:

  <stem>_time.png            per-operation wall time, baseline engine vs libvips
  combined_time.png          all operations in one figure (only if >1 input file)
  rss.png                    peak RSS per operation (skipped when not measured,
                             e.g. any run on macOS - RssSampler needs /proc)
  vips_startup_overhead.png  libvips in-process vs the same op through the CLI
                             (only when a run included the `vipscli` engine)

Deliberately NOT charted: stddev/MAD (already drawn as error bars), warmup times
and exit codes (diagnostics), and output file sizes - the last is a *validity
gate*, not a metric, and is surfaced as a hatched bar + footnote instead.

Usage:
  ./tools/plot_benchmarks.py results-ffm/jpg
  ./tools/plot_benchmarks.py results-ffm/jpg/standard_resize_ffm.json -o /tmp/charts
"""

import argparse
import json
import re
import sys
from collections import OrderedDict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.patches as mpatches
import matplotlib.pyplot as plt
import numpy as np

try:
    plt.style.use("seaborn-v0_8-whitegrid")
except OSError:
    plt.style.use("ggplot")
plt.rcParams["font.sans-serif"] = "DejaVu Sans"
plt.rcParams["axes.edgecolor"] = "#cccccc"
plt.rcParams["axes.linewidth"] = 0.8

COLOR_GM = "#2b5c8f"       # GraphicsMagick
COLOR_IM = "#7b4397"       # ImageMagick
COLOR_VIPS = "#e05d44"     # libvips, in-process
COLOR_VIPS_CLI = "#f0a08c" # libvips, forked CLI

# Order matters: 'vips-ffm'/'vips-cli' must be tried before the bare 'vips'.
# NB the bare `vips` rows come from benchmark_resize.sh, where they ARE the CLI -
# labelling them "in-process" would be a lie, hence the separate display names.
ENGINES = [
    ("vips-ffm", "vips", "libvips (in-process, vips-ffm)", COLOR_VIPS),
    ("vips-cli", "vipscli", "libvips (CLI subprocess)", COLOR_VIPS_CLI),
    ("vips", "vipscli", "libvips (CLI subprocess)", COLOR_VIPS_CLI),
    ("GM", "gm", "GraphicsMagick (CLI subprocess)", COLOR_GM),
    ("IM", "im", "ImageMagick (CLI subprocess)", COLOR_IM),
]
BASELINE_ENGINES = ("gm", "im")            # what libvips is being compared AGAINST
LIBVIPS_ENGINES = ("vips", "vipscli")      # prefer in-process; fall back to CLI rows
SUBPROCESS_ENGINES = ("gm", "im", "vipscli")  # engines whose memory lands in a CHILD process

DISPLAY = {k: (label, color) for _, k, label, color in ENGINES}
DISPLAY["vips"] = ("libvips (in-process, vips-ffm)", COLOR_VIPS)


def describe_source(data, fallback_path):
    """A subtitle that identifies the fixture by what a reader actually needs -
    format, resolution, size - not by a filename that means nothing to them.

    Prefers the source_* fields the Java harness exports. hyperfine's own export has
    none of them, so fall back to sniffing the file if it is still on disk."""
    fmt = data.get("source_format")
    w, h = data.get("source_width"), data.get("source_height")
    size = data.get("source_bytes")
    path = Path(data.get("source") or fallback_path)

    if not fmt:
        fmt = path.suffix.lstrip(".") or "?"
    if size is None and path.is_file():
        size = path.stat().st_size
    if (not w or not h) and path.is_file():
        w, h = sniff_dimensions(path)

    parts = [fmt.upper()]
    if w and h:
        parts.append(f"{w}×{h}")
    if size:
        parts.append(f"{size / (1024 * 1024):.1f} MB")
    return "  ·  ".join(parts)


def sniff_dimensions(path: Path):
    """Minimal PNG/JPEG/WebP header read, so the hyperfine-export path can still show a
    resolution without pulling in Pillow. Returns (None, None) for anything else
    (HEIC/AVIF included - those come from the Java export, which uses libvips)."""
    try:
        with path.open("rb") as f:
            head = f.read(32)
            if head[:8] == b"\x89PNG\r\n\x1a\n":
                w, h = int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")
                return w, h
            if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
                f.seek(0)
                buf = f.read(64)
                if buf[12:16] == b"VP8X":
                    return (int.from_bytes(buf[24:27], "little") + 1,
                            int.from_bytes(buf[27:30], "little") + 1)
                if buf[12:16] == b"VP8 ":
                    return (int.from_bytes(buf[26:28], "little") & 0x3FFF,
                            int.from_bytes(buf[28:30], "little") & 0x3FFF)
                if buf[12:16] == b"VP8L":
                    b = int.from_bytes(buf[21:25], "little")
                    return (b & 0x3FFF) + 1, ((b >> 14) & 0x3FFF) + 1
                return None, None
            if head[:2] == b"\xff\xd8":  # JPEG: walk the segment chain to SOFn
                f.seek(2)
                while True:
                    marker = f.read(2)
                    if len(marker) < 2 or marker[0] != 0xFF:
                        return None, None
                    length = int.from_bytes(f.read(2), "big")
                    if marker[1] in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                                     0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                        f.read(1)
                        return (int.from_bytes(f.read(2), "big"),  # height first
                                int.from_bytes(f.read(2), "big"))[::-1]
                    f.seek(length - 2, 1)
    except (OSError, ValueError):
        pass
    return None, None


def load_results(path: Path):
    """Returns (source_label, [row, ...]). Tolerates the non-strict JSON that
    benchmark_resize.sh produces by prepending the source filename."""
    text = path.read_text()
    brace = text.find("{")
    if brace < 0:
        raise ValueError(f"{path}: no JSON object found")
    prefix = text[:brace].strip()
    data = json.loads(text[brace:])
    source = describe_source(data, prefix or path.stem)

    rows = []
    for r in data.get("results", []):
        command = r["command"]
        engine = r.get("engine")
        operation = command
        for token, key, _, _ in ENGINES:
            m = re.match(rf"^{re.escape(token)}\s+", command)
            if m:
                engine = engine or key
                operation = command[m.end():]
                break
        rows.append({
            "command": command,
            "engine": engine or "unknown",
            "operation": operation.strip(),
            "mean": r["mean"],
            "stddev": r.get("stddev") or 0.0,
            "rss_self_kb": r.get("rss_self_kb", -1),
            "rss_children_kb": r.get("rss_children_kb", -1),
            "vips_tracked_peak_kb": r.get("vips_tracked_peak_kb", -1),
            "output_bytes": r.get("output_bytes", -1),
        })
    return source, rows


def group_by_operation(rows):
    """operation -> {engine: row}, preserving first-seen operation order."""
    grouped = OrderedDict()
    for r in rows:
        grouped.setdefault(r["operation"], {})[r["engine"]] = r
    return grouped


def pick(by_engine, keys):
    for k in keys:
        if k in by_engine:
            return k, by_engine[k]
    return None, None


def attributable_memory_mb(row):
    """Peak memory that actually belongs to this engine - a DIFFERENT metric per engine,
    because no single one is meaningful for both.

    Forked engines (gm/im/vips-cli): peak RSS of the child. Genuine per-image figure,
    since every rep forks a fresh process the kernel accounts for separately.

    In-process libvips: peak of libvips' own vips_tracked_get_mem(). NOT rss_self, which
    is unusable here - glibc does not return freed memory to the OS, so the first case
    grows the JVM's RSS and every later case baselines at that elevated level, reuses the
    same free lists and reads ~0. That is a measurement artifact, not low memory use."""
    kb = row["rss_children_kb"] if row["engine"] in SUBPROCESS_ENGINES else row["vips_tracked_peak_kb"]
    return kb / 1024.0 if kb is not None and kb >= 0 else None


def short_label(operation):
    """'mobileFull 1125x2000 (jpg q85)' -> 'mobileFull (jpg)\\n1125x2000'.

    The full command strings are far too long to sit side by side on an axis; keep
    the operation name, its output dimensions, and the format when the name doesn't
    already carry it."""
    m = re.match(r"^(.*?)\s+(\d+x\d+)(?:\s*\((.*)\))?", operation)
    if not m:
        return operation
    name, dims, paren = m.group(1), m.group(2), (m.group(3) or "")
    fmt = next((f for f in ("jpg", "png", "webp") if re.search(rf"\b{f}\b", paren)), None)
    if fmt and fmt not in name.lower():
        name = f"{name} ({fmt})"
    return f"{name}\n{dims}"


def apply_scale(ax, values, base_ylabel):
    """Always LINEAR, always zero-based, so bar height is directly proportional to the
    value: a bar for 3x the time is 3x as tall.

    An earlier version switched to a log axis when the spread got wide (the 2.2s IM bleed
    case next to a 0.05s thumbnail). That made short bars legible but broke the one thing a
    bar chart is for - on a log axis a 3x difference is not a 3x difference in height, and
    a reader comparing bars by eye gets the wrong answer. The value is printed above every
    bar, so a short bar is still readable; a misleading one is not fixable."""
    ax.set_ylim(0, max(values) * 1.18)
    ax.set_ylabel(base_ylabel, fontsize=11, fontweight="bold")


def annotate_bars(ax, rects, unit="s", font_size=8.5):
    for rect in rects:
        h = rect.get_height()
        if h <= 0:
            continue
        text = f"{h:.3f}{unit}" if unit == "s" else f"{h:.1f}{unit}"
        ax.annotate(text, xy=(rect.get_x() + rect.get_width() / 2, h),
                    xytext=(0, 4), textcoords="offset points",
                    ha="center", va="bottom", fontsize=font_size, fontweight="bold")


def size_mismatch(a, b, tolerance=0.10):
    """True when the two engines' outputs differ enough that encoder settings are
    not comparable - the row is then measuring whose default compression effort is
    lower, not which library is faster."""
    if not a or not b or a["output_bytes"] < 0 or b["output_bytes"] < 0 or a["output_bytes"] == 0:
        return False
    return abs(b["output_bytes"] - a["output_bytes"]) / a["output_bytes"] > tolerance


def time_chart(grouped, source, title, out_path):
    ops, base_rows, vips_rows, vips_keys = [], [], [], set()
    for op, by_engine in grouped.items():
        _, base = pick(by_engine, BASELINE_ENGINES)
        vkey, vips = pick(by_engine, LIBVIPS_ENGINES)
        if base is None or vips is None:
            continue
        ops.append(op)
        base_rows.append(base)
        vips_rows.append(vips)
        vips_keys.add(vkey)
    if not ops:
        return None

    flagged = [size_mismatch(b, v) for b, v in zip(base_rows, vips_rows)]
    labels = []
    for op, b, v, flag in zip(ops, base_rows, vips_rows, flagged):
        ratio = b["mean"] / v["mean"] if v["mean"] else 0
        # "0.54x faster" is nonsense - say slower when it is slower.
        verdict = f"{ratio:.2f}x faster" if ratio >= 1 else f"{1 / ratio:.2f}x SLOWER" if ratio else "n/a"
        labels.append(f"{short_label(op)}\n{verdict}" + (" *" if flag else ""))

    x = np.arange(len(ops))
    width = 0.35
    fig, ax = plt.subplots(figsize=(max(7, 1.85 * len(ops)), 6.3), dpi=200)

    base_colors = [DISPLAY.get(r["engine"], ("", COLOR_GM))[1] for r in base_rows]
    r1 = ax.bar(x - width / 2, [r["mean"] for r in base_rows], width,
                yerr=[r["stddev"] for r in base_rows], color=base_colors,
                capsize=4, edgecolor="black", linewidth=0.5)
    vips_colors = [DISPLAY.get(r["engine"], ("", COLOR_VIPS))[1] for r in vips_rows]
    r2 = ax.bar(x + width / 2, [r["mean"] for r in vips_rows], width,
                yerr=[r["stddev"] for r in vips_rows], color=vips_colors,
                capsize=4, edgecolor="black", linewidth=0.5)
    for bar, flag in zip(list(r1) + list(r2), flagged + flagged):
        if flag:
            bar.set_hatch("//")

    ax.set_title(f"{title}\nSource: {source}", fontsize=13, fontweight="bold", pad=14)
    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=9)
    apply_scale(ax, [r["mean"] for r in base_rows + vips_rows],
                "Mean wall time (seconds, lower is better)")
    annotate_bars(ax, r1)
    annotate_bars(ax, r2)

    engines_present = {r["engine"] for r in base_rows} | vips_keys
    handles = [mpatches.Patch(facecolor=DISPLAY[e][1], label=DISPLAY[e][0],
                              edgecolor="black", linewidth=0.5)
               for e in ("gm", "im", "vips", "vipscli") if e in engines_present]
    if any(flagged):
        # Keep the caveat attached to the marker rather than as a corner note, so the
        # hatching can never appear unexplained.
        handles.append(mpatches.Patch(facecolor="white", hatch="//", edgecolor="black",
                                      linewidth=0.5,
                                      label="* output sizes differ >10% — speedup unvalidated"))
    ax.legend(handles=handles, fontsize=10, frameon=True, facecolor="white", edgecolor="none")

    fig.tight_layout()
    fig.savefig(out_path, dpi=200)
    plt.close(fig)
    return out_path


def rss_chart(grouped, source, out_path):
    ops, base_vals, vips_vals, base_colors = [], [], [], []
    for op, by_engine in grouped.items():
        _, base = pick(by_engine, BASELINE_ENGINES)
        _, vips = pick(by_engine, LIBVIPS_ENGINES)
        if base is None or vips is None:
            continue
        b_mb, v_mb = attributable_memory_mb(base), attributable_memory_mb(vips)
        if b_mb is None or v_mb is None:
            continue
        ops.append(op)
        base_vals.append(b_mb)
        vips_vals.append(v_mb)
        base_colors.append(DISPLAY.get(base["engine"], ("", COLOR_GM))[1])
    if not ops:
        return None  # not measured (no /proc) - emit nothing rather than a chart of zeros

    x = np.arange(len(ops))
    width = 0.35
    fig, ax = plt.subplots(figsize=(max(7, 1.85 * len(ops)), 6.3), dpi=200)
    r1 = ax.bar(x - width / 2, base_vals, width, color=base_colors,
                capsize=4, edgecolor="black", linewidth=0.5)
    r2 = ax.bar(x + width / 2, vips_vals, width, color=COLOR_VIPS,
                capsize=4, edgecolor="black", linewidth=0.5)

    ax.set_title(f"Peak memory per operation (different metric per engine)\nSource: {source}", fontsize=13, fontweight="bold", pad=14)
    ax.set_xticks(x)
    # Deliberately NO "Nx less" here, unlike the time chart. These two bars are different
    # quantities - a whole child process' RSS versus libvips' tracked image buffers - so their
    # ratio is not a memory saving and must not be presented as one.
    ax.set_xticklabels([short_label(op) for op in ops], fontsize=9)
    apply_scale(ax, base_vals + vips_vals, "Peak memory attributable to the engine (MB)")
    annotate_bars(ax, r1, unit="MB")
    annotate_bars(ax, r2, unit="MB")
    # The legend carries the "these are two different quantities" caveat, so it travels
    # with the chart without needing a corner note.
    ax.legend(handles=[mpatches.Patch(facecolor=c, label=l, edgecolor="black", linewidth=0.5)
                       for c, l in [(COLOR_GM, "GM/IM: peak RSS of the forked child process"),
                                    (COLOR_VIPS, "libvips: peak vips_tracked_get_mem() in-process")]],
              fontsize=9, frameon=True, facecolor="white", edgecolor="none")
    fig.tight_layout()
    fig.savefig(out_path, dpi=200)
    plt.close(fig)
    return out_path


def startup_chart(grouped, source, out_path):
    """The reason this whole in-process harness exists: how much of the `vips` CLI's
    time is process startup rather than image work."""
    ops, ffm, cli = [], [], []
    for op, by_engine in grouped.items():
        if "vips" in by_engine and "vipscli" in by_engine:
            ops.append(op)
            ffm.append(by_engine["vips"]["mean"])
            cli.append(by_engine["vipscli"]["mean"])
    if not ops:
        return None

    x = np.arange(len(ops))
    width = 0.35
    fig, ax = plt.subplots(figsize=(max(8, 2.6 * len(ops)), 5.8), dpi=200)
    r1 = ax.bar(x - width / 2, cli, width, color=COLOR_VIPS_CLI,
                edgecolor="black", linewidth=0.5, label="libvips via CLI (forked per image)")
    r2 = ax.bar(x + width / 2, ffm, width, color=COLOR_VIPS,
                edgecolor="black", linewidth=0.5, label="libvips in-process (vips-ffm)")
    annotate_bars(ax, r1)
    annotate_bars(ax, r2)

    for xi, (c, f) in enumerate(zip(cli, ffm)):
        ax.annotate(f"+{(c - f) * 1000:.0f} ms", xy=(xi, max(c, f)), xytext=(0, 22),
                    textcoords="offset points", ha="center", fontsize=9,
                    fontweight="bold", color="#8a3324")

    ax.set_ylabel("Mean wall time (seconds)", fontsize=11, fontweight="bold")
    ax.set_title(f"Cost of shelling out to the vips CLI\nSource: {source}",
                 fontsize=13, fontweight="bold", pad=14)
    ax.set_xticks(x)
    ax.set_xticklabels([short_label(o) for o in ops], fontsize=9)
    ax.set_ylim(0, max(cli + ffm) * 1.30)
    ax.legend(fontsize=10, frameon=True, facecolor="white", edgecolor="none")
    fig.tight_layout()
    fig.savefig(out_path, dpi=200)
    plt.close(fig)
    return out_path


def main():
    ap = argparse.ArgumentParser(description="Render benchmark JSON into charts.")
    ap.add_argument("inputs", nargs="+", type=Path,
                    help="result .json files, or a directory containing them")
    ap.add_argument("-o", "--out-dir", type=Path, default=None,
                    help="where to write PNGs (default: <first input dir>/charts)")
    args = ap.parse_args()

    files = []
    for p in args.inputs:
        if p.is_dir():
            files.extend(sorted(f for f in p.glob("*resize*.json")))
        elif p.is_file():
            files.append(p)
        else:
            print(f"skipping (not found): {p}", file=sys.stderr)
    if not files:
        print("No result JSON found.", file=sys.stderr)
        return 1

    out_dir = args.out_dir or (files[0].parent / "charts")
    out_dir.mkdir(parents=True, exist_ok=True)

    all_rows, source, written = [], None, []
    for f in files:
        src, rows = load_results(f)
        source = source or src
        all_rows.extend(rows)
        title = f.stem.replace("_ffm", "").replace("_", " ").title()
        p = time_chart(group_by_operation(rows), src, title, out_dir / f"{f.stem}_time.png")
        if p:
            written.append(p)

    combined = group_by_operation(all_rows)
    if len(files) > 1:
        p = time_chart(combined, source, "All operations", out_dir / "combined_time.png")
        if p:
            written.append(p)
    for p in (rss_chart(combined, source, out_dir / "rss.png"),
              startup_chart(combined, source, out_dir / "vips_startup_overhead.png")):
        if p:
            written.append(p)

    for p in written:
        print(f"wrote {p}")
    if not any(p.name == "rss.png" for p in written):
        print("skipped rss.png — no RSS in the input (RssSampler needs /proc; "
              "run in the Linux container to get memory numbers)")
    if not any(p.name == "vips_startup_overhead.png" for p in written):
        print("skipped vips_startup_overhead.png — no vips-cli rows "
              "(re-run with BENCH_ENGINES=gm,im,vips,vipscli)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

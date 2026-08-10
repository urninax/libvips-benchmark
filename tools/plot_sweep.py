#!/usr/bin/env python3
"""
Render run-sweep.sh results into charts.

WHY A SECOND SCRIPT: tools/plot_benchmarks.py reads the LOCAL harness's strict JSON
(bench.jar local-resize). The S3 harness writes no structured output at all - the
`##RESULT##` lines each `run-variant` child emits are consumed by the orchestrating
parent (Main.runVariantSubprocess) and never reach the log, so all that survives on
disk is the human-readable ResultTable text that run-sweep.sh tee'd into
results/summary-<profile>.txt. This script parses that table.

Everything is derived from the parsed data - no hardcoded numbers - so a chart can
never silently drift from the run it claims to show.

DELIBERATELY NOT CHARTED: every memory column (totalMB, MB/thru, rssChild,
vipsPeak). totalMB is a single peak-of-samples over the isolated process and was
shown to contradict vipsPeak's own signal on real sweep output (a jpg run reported
HIGHER totalMB than a png run that needs visibly more memory by every other
measure). rssChild and vipsPeak are different quantities that must never be read as
a ratio - vipsPeak counts only vips_tracked_malloc image buffers, ~34% of real
process memory. Until the S3 harness grows the coverage cross-check the local
benchmarks already have (LocalMeasure.printMemoryCoverage), none of it is chartable
without misleading someone. Time, throughput, CPU and overlap are all sound.

Charts emitted (each only when the data supports it):
  sweep_throughput.png    jobs/sec per variant, one panel per (operation, profile)
  sweep_latency.png       p50/p95/p99 per variant - tail behaviour, log scale
  sweep_streaming.png     the sweep's actual question: how much does streamed FETCH
                          buy over buffered, and does it grow with network latency?
  sweep_mechanism.png     cpu% and overlap ratio - WHY streaming wins when it wins

Usage:
  ./tools/plot_sweep.py results
  ./tools/plot_sweep.py results/summary-s3real.txt -o /tmp/charts
"""

import argparse
import re
import sys
import textwrap
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

# Validated categorical trio (all-pairs, light AND dark - see the dataviz palette).
# Colour carries FETCH MODE, not variant identity, and that is the point: the put
# axis is degenerate (a streamed put never crosses S3's 5MiB multipart threshold on
# these output sizes, so b1 is b0 with extra copying and b3 is b2 with extra
# copying). Painting b0/b1 the same and b2/b3 the same makes that visible instead of
# implying four distinct things are being compared.
COLOR_BASELINE = "#eb6834"   # GM / IM, forked per job
COLOR_BUFFERED = "#2a78d6"   # vips, buffered fetch  (b0, b1)
COLOR_STREAMED = "#1baf7a"   # vips, streamed fetch  (b2, b3)

INK = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#898781"

# Ordered fastest-link-first so the streaming chart's x-axis is monotonic in latency.
# Anything not listed sorts last, alphabetically.
PROFILE_ORDER = ["none", "s3fast", "s3real", "s3cross", "az", "region", "degraded"]
PROFILE_BLURB = {
    "none": "proxy pass-through",
    "s3fast": "~85 MB/s, 25ms",
    "s3real": "~30 MB/s, 30ms",
    "s3cross": "~12 MB/s, cross-region",
    "az": "10 Mbit/s (NOT real S3)",
    "region": "2 Mbit/s (NOT real S3)",
    "degraded": "0.4 Mbit/s (NOT real S3)",
}

# ResultTable emits exactly these 18 numeric fields after the variant name.
NUMERIC_FIELDS = [
    "workers", "queue", "drain_s", "sd", "thru_s", "total_mb", "mb_per_thru",
    "rss_child", "vips_peak", "cpu_pct", "gc_n", "gc_ms", "overlap",
    "p50_ms", "p95_ms", "p99_ms", "max_ms", "fail",
]


# ---- parsing -------------------------------------------------------------

def parse_summary(path):
    """One summary-<profile>.txt -> (meta dict, {section_title: [row dicts]}).

    Parses from the RIGHT: the variant name contains spaces, brackets and parens, and
    can overflow ResultTable's %-46s field, so splitting left-to-right on whitespace
    is not safe. The numeric column COUNT is fixed at 18, though, so the name is
    simply whatever precedes the last 18 tokens.
    """
    text = Path(path).read_text(errors="replace")
    meta = {"profile": _profile_of(path)}

    m = re.search(r"^cores=(\d+).*$", text, re.M)
    if m:
        for k, v in re.findall(r"(\w+)=(\[[^\]]*\]|\S+)", m.group(0)):
            meta[k] = v

    sections, current = OrderedDict(), None
    for line in text.splitlines():
        sec = re.match(r"^===\s+(.*?)\s+===\s*$", line)
        if sec:
            title = sec.group(1)
            # Skip the run banner; only keep real result sections.
            if not title.lower().startswith("libvips-benchmark"):
                current = title
                sections.setdefault(current, [])
            continue
        if current is None:
            continue
        row = _parse_row(line)
        if row:
            row["profile"] = meta["profile"]
            sections[current].append(row)
    return meta, {k: v for k, v in sections.items() if v}


def _parse_row(line):
    if not line.strip() or line.lstrip().startswith("variant"):
        return None
    # A failing row appends "  [FIRST ERROR: ...]" - cut it before counting tokens.
    line = line.split("[FIRST ERROR:")[0]
    tokens = line.split()
    if len(tokens) < len(NUMERIC_FIELDS) + 1:
        return None
    nums, name = tokens[-len(NUMERIC_FIELDS):], " ".join(tokens[: -len(NUMERIC_FIELDS)])
    try:
        vals = [float(t) for t in nums]
    except ValueError:
        return None  # progress/log line that happens to be long enough
    row = dict(zip(NUMERIC_FIELDS, vals))
    row["name"] = name
    row["engine"], row["variant"] = _classify(name)
    return row


def _profile_of(path):
    m = re.search(r"summary-(.+?)\.txt$", Path(path).name)
    return m.group(1) if m else Path(path).stem


def _classify(name):
    """-> (engine, short variant label). engine is 'baseline' | 'buffered' | 'streamed'."""
    m = re.search(r"\bb([0-3])\b", name)
    if m:
        # b0/b1 buffered fetch, b2/b3 streamed fetch (Mode enum in VipsStreamingVariant).
        return ("buffered" if m.group(1) in "01" else "streamed"), f"b{m.group(1)}"
    head = name.split()[0]
    return "baseline", head


def sort_key(row):
    return (0 if row["engine"] == "baseline" else 1, row["variant"])


def profile_key(p):
    return (PROFILE_ORDER.index(p) if p in PROFILE_ORDER else len(PROFILE_ORDER), p)


COLOR_OF = {"baseline": COLOR_BASELINE, "buffered": COLOR_BUFFERED, "streamed": COLOR_STREAMED}


# ---- shared chart furniture ---------------------------------------------

def _style_axes(ax, ylabel, log=False):
    ax.set_ylabel(ylabel, fontsize=9, color=INK_SECONDARY)
    if log:
        ax.set_yscale("log")
    ax.tick_params(axis="both", labelsize=8.5, colors=INK_MUTED)
    ax.grid(True, axis="y", linewidth=0.6, alpha=0.5)
    ax.grid(False, axis="x")
    for s in ax.spines.values():
        s.set_visible(False)


def _label_bars(ax, rects, fmt="{:.0f}", size=7.5):
    """Direct value labels. Not optional: the aqua slot sits below 3:1 on the light
    surface, and the palette's relief rule makes visible labels the mitigation."""
    for r in rects:
        h = r.get_height()
        if not np.isfinite(h) or h <= 0:
            continue
        ax.annotate(fmt.format(h), (r.get_x() + r.get_width() / 2, h),
                    xytext=(0, 2.5), textcoords="offset points",
                    ha="center", va="bottom", fontsize=size, fontweight="bold",
                    color=INK_SECONDARY)


def _frame(fig, fig_w, fig_h, title, subtitle, legend_entries, footnote):
    """Places title / subtitle / legend / footnote and returns the tight_layout rect.

    Budgets are in ABSOLUTE INCHES converted to fractions, not raw fractions: a fixed
    fraction reserves far too little on a short figure (0.075 of a 4.3in figure is
    0.32in, which cannot hold three stacked header lines) and the subtitle ends up
    printed through the legend. Footnotes are wrapped so wide text can't clip off
    either edge - the wrap width is DERIVED from figure width (roughly 11 chars per
    inch at these font sizes) rather than fixed, because a fixed wrap overflows any
    figure narrower than it assumed.
    """
    wrap_at = max(40, int(fig_w * 11))

    def f(inches):
        return inches / fig_h

    fig.text(0.5, 1 - f(0.22), title, ha="center", va="center",
             fontsize=12.5, fontweight="bold", color=INK)
    y = 0.42
    if subtitle:
        for line in textwrap.wrap(subtitle, wrap_at):
            fig.text(0.5, 1 - f(y), line, ha="center", va="center",
                     fontsize=8.8, color=INK_SECONDARY)
            y += 0.17
    header_in = y + 0.06
    if legend_entries:
        handles = [mpatches.Patch(facecolor=c, label=l, edgecolor="none")
                   for l, c in legend_entries]
        fig.legend(handles=handles, loc="upper center",
                   bbox_to_anchor=(0.5, 1 - f(header_in)),
                   ncol=len(legend_entries), fontsize=9, frameon=False)
        header_in += 0.34

    foot_in = 0.10
    if footnote:
        lines = textwrap.wrap(footnote, wrap_at)
        for i, line in enumerate(reversed(lines)):
            fig.text(0.5, f(0.12 + i * 0.145), line, ha="center", va="center",
                     fontsize=7.6, color=INK_MUTED)
        foot_in = 0.20 + 0.145 * len(lines)

    return (0, f(foot_in), 1, 1 - f(header_in + 0.08))


def _grid_shape(n):
    cols = min(n, 2)
    return int(np.ceil(n / cols)), cols


# ---- charts --------------------------------------------------------------

def chart_throughput(panels, meta, out):
    """Jobs/sec per variant, one panel per (operation, profile)."""
    keys = sorted(panels, key=lambda k: (k[0], profile_key(k[1])))
    nrows, ncols = _grid_shape(len(keys))
    fig_h = 4.3 * nrows + 1.5
    fig_w = 6.6 * ncols
    fig, axes = plt.subplots(nrows, ncols, figsize=(fig_w, fig_h),
                             squeeze=False, dpi=200)
    for idx, key in enumerate(keys):
        ax = axes[idx // ncols][idx % ncols]
        rows = sorted(panels[key], key=sort_key)
        x = np.arange(len(rows))
        rects = ax.bar(x, [r["thru_s"] for r in rows], 0.62,
                       color=[COLOR_OF[r["engine"]] for r in rows])
        _label_bars(ax, rects, "{:.1f}")
        base = next((r["thru_s"] for r in rows if r["engine"] == "baseline"), None)
        labels = []
        for r in rows:
            speedup = f"\n{r['thru_s']/base:.1f}x" if base and r["engine"] != "baseline" else ""
            labels.append(f"{r['variant']}{speedup}")
        ax.set_xticks(x)
        ax.set_xticklabels(labels, fontsize=8.5, color=INK_SECONDARY)
        _style_axes(ax, "throughput (jobs/sec)")
        blurb = PROFILE_BLURB.get(key[1], "")
        ax.set_title(f"{key[0]} - {key[1]}" + (f"  ({blurb})" if blurb else ""),
                     fontsize=10, fontweight="bold", color=INK, pad=8)
    for j in range(len(keys), nrows * ncols):
        axes[j // ncols][j % ncols].axis("off")

    rect = _frame(fig, fig_w, fig_h, "Throughput per variant",
                  _run_line(meta),
                  [("GM / IM (forked per job)", COLOR_BASELINE),
                   ("vips - buffered fetch (b0, b1)", COLOR_BUFFERED),
                   ("vips - streamed fetch (b2, b3)", COLOR_STREAMED)],
                  None)
    fig.tight_layout(rect=rect)
    fig.savefig(out, dpi=200)
    plt.close(fig)
    return out


def chart_latency(panels, meta, out):
    """p50 -> p99 as a RANGE PLOT, not bars.

    Deliberately not a bar chart: a baseline that spans 10x within one panel needs a
    log axis, and a bar on a log axis is a lie - bar length encodes magnitude measured
    from zero, and a log axis has no zero, so matplotlib clips the bar at whatever the
    axis floor happens to be and the lengths stop meaning anything. A range plot
    encodes value by POSITION, which is exactly what a log axis supports, and it shows
    the spread (how far p99 sits above p50) far better than three bars ever did.
    """
    keys = sorted(panels, key=lambda k: (k[0], profile_key(k[1])))
    nrows, ncols = _grid_shape(len(keys))
    fig_h = 4.3 * nrows + 1.5
    fig_w = 6.6 * ncols
    fig, axes = plt.subplots(nrows, ncols, figsize=(fig_w, fig_h),
                             squeeze=False, dpi=200)
    for idx, key in enumerate(keys):
        ax = axes[idx // ncols][idx % ncols]
        rows = sorted(panels[key], key=sort_key)
        for i, r in enumerate(rows):
            c = COLOR_OF[r["engine"]]
            ax.plot([i, i], [r["p50_ms"], r["p99_ms"]], color=c, linewidth=2,
                    solid_capstyle="round", zorder=2)
            # p50 hollow, p95 mid, p99 solid - a filled ring on each so overlapping
            # markers stay separable against the line and each other.
            ax.plot(i, r["p50_ms"], "o", ms=7, mfc="white", mec=c, mew=2, zorder=3)
            ax.plot(i, r["p95_ms"], "o", ms=6, color=c, alpha=0.55, zorder=3)
            ax.plot(i, r["p99_ms"], "o", ms=8.5, color=c, mec="white", mew=1.2, zorder=4)
            ax.annotate(f"{r['p99_ms']:.0f}", (i, r["p99_ms"]), xytext=(0, 9),
                        textcoords="offset points", ha="center", fontsize=7.6,
                        fontweight="bold", color=INK_SECONDARY)
            ax.annotate(f"{r['p50_ms']:.0f}", (i, r["p50_ms"]), xytext=(0, -15),
                        textcoords="offset points", ha="center", fontsize=7.2,
                        color=INK_MUTED)
        ax.set_xlim(-0.6, len(rows) - 0.4)
        ax.set_xticks(range(len(rows)))
        ax.set_xticklabels([r["variant"] for r in rows], fontsize=8.5, color=INK_SECONDARY)
        _style_axes(ax, "per-job latency (ms, log)", log=True)
        lo = min(r["p50_ms"] for r in rows)
        hi = max(r["p99_ms"] for r in rows)
        ax.set_ylim(lo * 0.72, hi * 1.5)
        ax.set_title(f"{key[0]} - {key[1]}", fontsize=10, fontweight="bold", color=INK, pad=8)
    for j in range(len(keys), nrows * ncols):
        axes[j // ncols][j % ncols].axis("off")

    rect = _frame(fig, fig_w, fig_h, "Latency spread: p50 to p99",
                  _run_line(meta) + "   ·   hollow marker = p50, faint = p95, solid = p99",
                  [("GM / IM", COLOR_BASELINE), ("vips buffered fetch", COLOR_BUFFERED),
                   ("vips streamed fetch", COLOR_STREAMED)],
                  "Log axis, so read POSITION not bar length. A short line means a tight distribution "
                  "(predictable per-job latency); a long one means a heavy tail.")
    fig.tight_layout(rect=rect)
    fig.savefig(out, dpi=200)
    plt.close(fig)
    return out


def chart_streaming(panels, meta, out):
    """The sweep's actual question: what does streamed FETCH buy over buffered, and
    does it grow with network latency?

    Compares like with like - b2 vs b0 (both buffered put) and b3 vs b1 (both
    streamed put) - so the delta isolates the fetch axis and nothing else.
    """
    pairs = [("b0", "b2"), ("b1", "b3")]
    ops = sorted({k[0] for k in panels})
    profiles = sorted({k[1] for k in panels}, key=profile_key)

    series = OrderedDict()  # op -> profile -> [pct gain, ...]
    for op in ops:
        for prof in profiles:
            rows = {r["variant"]: r for r in panels.get((op, prof), [])}
            gains = [100.0 * (rows[s]["thru_s"] - rows[b]["thru_s"]) / rows[b]["thru_s"]
                     for b, s in pairs if b in rows and s in rows and rows[b]["thru_s"] > 0]
            if gains:
                series.setdefault(op, OrderedDict())[prof] = gains
    if not series:
        return None

    fig_h = 5.6
    fig_w = max(10.5, 2.6 * len(profiles) * len(series))
    fig, ax = plt.subplots(figsize=(fig_w, fig_h), dpi=200)
    n = len(series)
    w = 0.74 / n
    xs = np.arange(len(profiles))
    for si, (op, by_prof) in enumerate(series.items()):
        vals = [float(np.mean(by_prof.get(p, [np.nan]))) for p in profiles]
        off = (si - (n - 1) / 2) * w
        rects = ax.bar(xs + off, vals, w * 0.9,
                       color=COLOR_STREAMED if si == 0 else COLOR_BUFFERED, label=op)
        _label_bars(ax, rects, "{:+.0f}%")
    ax.axhline(0, color="#c3c2b7", linewidth=1)
    ax.set_xticks(xs)
    ax.set_xticklabels([f"{p}\n{PROFILE_BLURB.get(p,'')}" for p in profiles],
                       fontsize=8.5, color=INK_SECONDARY)
    _style_axes(ax, "throughput gain from streaming the fetch (%)")
    if len(series) > 1:
        ax.legend(fontsize=9, frameon=False, title="operation", title_fontsize=8.5)

    single = len(profiles) < 2
    rect = _frame(fig, fig_w, fig_h, "Does streaming the S3 fetch actually help?",
                  _run_line(meta) + "   ·   mean of (b2 vs b0) and (b3 vs b1) - isolates the fetch axis"
                  + ("   ·   operation: " + next(iter(series)) if len(series) == 1 else ""),
                  None,
                  ("ONE PROFILE ONLY this chart answers 'how much' but not 'does it grow with latency'. "
                   "Re-run with PROFILES=\"s3fast s3real s3cross\" for the trend."
                   if single else
                   "Streaming can only ever hide min(fetch, compute), so the gain should grow as the link slows "
                   "until fetch dominates compute entirely. az/region/degraded are 10/2/0.4 Mbit/s links, "
                   "20-70x slower than real S3, and will overstate it."))
    fig.tight_layout(rect=rect)
    fig.savefig(out, dpi=200)
    plt.close(fig)
    return out


def chart_mechanism(panels, meta, out):
    """cpu% and overlap - the WHY behind the throughput numbers.

    Buffered fetch parks a worker doing nothing while bytes arrive, so cores sit idle
    and cpu% is lower. Streamed fetch fills that gap with decode work. overlap =
    (fetch+process+upload)/total, so >1.0 means I/O genuinely ran concurrently with
    compute. Two separate panels, never a dual axis - they share no unit.
    """
    keys = sorted(panels, key=lambda k: (k[0], profile_key(k[1])))
    fig_h = 8.6
    fig_w = max(9.0, 5.4 * len(keys))
    fig, axes = plt.subplots(2, len(keys), figsize=(fig_w, fig_h),
                             squeeze=False, dpi=200)
    for ci, key in enumerate(keys):
        rows = sorted(panels[key], key=sort_key)
        x = np.arange(len(rows))
        colors = [COLOR_OF[r["engine"]] for r in rows]

        ax = axes[0][ci]
        _label_bars(ax, ax.bar(x, [r["cpu_pct"] for r in rows], 0.62, color=colors), "{:.0f}%")
        ax.set_xticks(x); ax.set_xticklabels([r["variant"] for r in rows], fontsize=8.5, color=INK_SECONDARY)
        _style_axes(ax, "CPU utilisation (%)")
        ax.set_ylim(0, 108)
        ax.set_title(f"{key[0]} - {key[1]}", fontsize=10, fontweight="bold", color=INK, pad=8)

        ax = axes[1][ci]
        _label_bars(ax, ax.bar(x, [r["overlap"] for r in rows], 0.62, color=colors), "{:.2f}")
        ax.axhline(1.0, color="#c3c2b7", linewidth=1, linestyle="--")
        ax.set_xticks(x); ax.set_xticklabels([r["variant"] for r in rows], fontsize=8.5, color=INK_SECONDARY)
        _style_axes(ax, "overlap ratio  (>1.0 = I/O overlapped compute)")
        ax.set_ylim(0, max(1.15, max(r["overlap"] for r in rows) * 1.18))

    rect = _frame(fig, fig_w, fig_h, "Why streaming wins when it wins",
                  _run_line(meta) + "   ·   top: CPU utilisation   ·   bottom: I/O–compute overlap",
                  [("GM / IM", COLOR_BASELINE), ("vips buffered fetch", COLOR_BUFFERED),
                   ("vips streamed fetch", COLOR_STREAMED)],
                  "Buffered fetch parks a worker while bytes arrive, so cores idle and cpu% drops; streamed fetch "
                  "fills that gap with decode work. An overlap of ~1.00 on a streamed row means the fetch finished "
                  "before compute started, not that streaming failed.")
    fig.tight_layout(rect=rect)
    fig.savefig(out, dpi=200)
    plt.close(fig)
    return out


def _run_line(meta):
    bits = [f"{k}={meta[k]}" for k in
            ("cores", "queueSize", "workers", "measuredReps", "inputFormat", "outputFormat", "allocator")
            if k in meta]
    return "  ".join(bits) if bits else "run config not found in log header"


# ---- main ---------------------------------------------------------------

def collect(paths):
    panels, metas = OrderedDict(), []
    for p in paths:
        meta, sections = parse_summary(p)
        if not sections:
            print(f"skipped {p} - no result tables found (crashed run, or only progress output?)",
                  file=sys.stderr)
            continue
        metas.append(meta)
        for title, rows in sections.items():
            op = title.replace(" resize", "").strip().lower()
            panels.setdefault((op, meta["profile"]), []).extend(rows)
    merged = {}
    for m in metas:
        merged.update({k: v for k, v in m.items() if k != "profile"})
    return panels, merged


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("target", help="results dir, or a single summary-<profile>.txt")
    ap.add_argument("-o", "--out-dir", default=None)
    args = ap.parse_args()

    t = Path(args.target)
    if t.is_dir():
        paths = sorted(t.glob("summary-*.txt"), key=lambda p: profile_key(_profile_of(p)))
    elif t.is_file():
        paths = [t]
    else:
        sys.exit(f"not found: {t}")
    if not paths:
        sys.exit(f"no summary-*.txt in {t} - run ./run-sweep.sh first "
                 f"(and check results/ is writable; a `tee: Permission denied` loses the whole log)")

    panels, meta = collect(paths)
    if not panels:
        sys.exit("nothing parseable in " + ", ".join(str(p) for p in paths))

    out_dir = Path(args.out_dir) if args.out_dir else (t if t.is_dir() else t.parent)
    out_dir.mkdir(parents=True, exist_ok=True)

    profiles = sorted({k[1] for k in panels}, key=profile_key)
    ops = sorted({k[0] for k in panels})
    print(f"parsed {sum(len(v) for v in panels.values())} rows · "
          f"operations={ops} · profiles={profiles}")

    written = [
        chart_throughput(panels, meta, out_dir / "sweep_throughput.png"),
        chart_latency(panels, meta, out_dir / "sweep_latency.png"),
        chart_streaming(panels, meta, out_dir / "sweep_streaming.png"),
        chart_mechanism(panels, meta, out_dir / "sweep_mechanism.png"),
    ]
    for w in written:
        if w:
            print(f"wrote {w}")
    if len(profiles) < 2:
        print('note: only one latency profile present - the streaming chart shows magnitude but not '
              'the latency TREND. Re-run with PROFILES="s3fast s3real s3cross" ./run-sweep.sh')
    print("note: no memory chart by design - see this script's docstring.")


if __name__ == "__main__":
    main()

package com.journi.bench.local;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The metadata record BOTH engines must produce, so the benchmark times equivalent work.
 *
 * WHY THIS TYPE EXISTS AT ALL: `gm identify -format` returns one ready-to-consume delimited line;
 * libvips returns a set of individually-addressed, type-decorated header fields. Timing "read a
 * header" against "read a header AND do all the caller's parsing" would be the same category of
 * error as comparing a shrink-on-load decode against a full decode. Production needs the parsed
 * record either way, so both engines are charged for producing it and both parse inside the timed
 * region.
 *
 * The differences this normalises away are real migration work, not benchmark noise - they are
 * printed verbatim by LocalMetadataBenchmark's equivalence gate rather than being silently
 * smoothed over:
 * <ul>
 *   <li>GM's format is {@code JPEG}; libvips' vips-loader is {@code jpegload}.</li>
 *   <li>GM returns GPS rationals comma-separated ({@code 48/1,13/1,2188/100}); libvips returns
 *       them space-separated ({@code 48/1 13/1 2188/100}).</li>
 *   <li>Every libvips EXIF string carries a type decoration:
 *       {@code "Apple (Apple, ASCII, 6 components, 6 bytes)"}. A caller that forgets to strip it
 *       stores the whole thing.</li>
 *   <li>A missing field is an empty string from GM but {@code null} from libvips (vips-ffm's
 *       getString returns null when image_get_typeof reports the field is absent).</li>
 * </ul>
 */
record ImageMeta(
    String format,
    Integer width,
    Integer height,
    Integer orientation,
    String make,
    String model,
    String gpsLongitude,
    String gpsLongitudeRef,
    String gpsLatitude,
    String gpsLatitudeRef
) {

    /** GraphicsMagickClient.readBasicImageInfo's format string. */
    static final String GM_BASIC_FMT = "%w;%h;%[EXIF:Orientation];%m";

    /** The full EXIF/GPS read. Field order is load-bearing - fromGmFull() indexes it positionally. */
    static final String GM_FULL_FMT =
        "%w;%h;%m;%[EXIF:Orientation];%[EXIF:Make];%[EXIF:Model];"
        + "%[EXIF:GPSLongitude];%[EXIF:GPSLongitudeRef];%[EXIF:GPSLatitude];%[EXIF:GPSLatitudeRef]";

    /**
     * Parses the GM_FULL_FMT line. Uses split(";", -1) with the negative limit so TRAILING empty
     * fields are preserved - the default split() drops them, and a photo with no GPS produces
     * exactly that (four empty trailing fields), which would otherwise throw
     * ArrayIndexOutOfBounds on the most common input in real traffic.
     */
    static ImageMeta fromGmFull(String line) {
        String[] p = line.trim().split(";", -1);
        return new ImageMeta(
            at(p, 2), intAt(p, 0), intAt(p, 1), intOrNull(at(p, 3)),
            at(p, 4), at(p, 5),
            at(p, 6), at(p, 7), at(p, 8), at(p, 9));
    }

    /**
     * Parses the GM_BASIC_FMT line - a DIFFERENT positional layout to GM_FULL_FMT
     * (w;h;orientation;format, not w;h;format;orientation). Production makes this call too, so the
     * benchmark parses it too; the resulting record is narrower by construction and carries no
     * EXIF beyond orientation.
     */
    static ImageMeta fromGmBasic(String line) {
        String[] p = line.trim().split(";", -1);
        return new ImageMeta(
            at(p, 3), intAt(p, 0), intAt(p, 1), intOrNull(at(p, 2)),
            null, null, null, null, null, null);
    }

    /**
     * Strips libvips' type decoration from a header string. Every EXIF field libvips exposes is
     * stored as {@code "<raw> (<interpreted>, <Type>, <n> components, <m> bytes)"}:
     * <pre>
     *   "Apple (Apple, ASCII, 6 components, 6 bytes)"                        -> "Apple"
     *   "1 (Top-left, Short, 1 components, 2 bytes)"                         -> "1"
     *   "48/1 13/1 2188/100 (48, 13, 21.88, Rational, 3 components, 24 bytes)" -> "48/1 13/1 2188/100"
     * </pre>
     *
     * Guarded on the {@code " bytes)"} suffix so a value that merely happens to end in a
     * parenthesis is returned untouched rather than truncated.
     *
     * KNOWN LIMITATION, and a real migration finding rather than a benchmark shortcut: when the
     * raw value ITSELF contains {@code " ("} - e.g. a lens model {@code "EF24mm f/1.4 (II)"} - the
     * decoration cannot be located unambiguously by any string rule. Both the first and the last
     * {@code " ("} yield a syntactically valid parse, and for non-string types the interpreted
     * copy does not equal the raw value, so they cannot be told apart either. This takes the FIRST
     * occurrence (the community-standard {@code split(" (")[0]}), which is correct for every field
     * this benchmark reads and for the overwhelming majority of real EXIF.
     *
     * Production must not depend on string-stripping for EXIF it actually cares about - read the
     * {@code exif-data} blob with a real EXIF parser instead. GM's delimited output has no
     * equivalent ambiguity, so this is a genuine cost of the migration, not a wash.
     */
    static String stripVipsDecoration(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (!t.endsWith(" bytes)")) return emptyToNull(t);
        int open = t.indexOf(" (");
        return open < 0 ? emptyToNull(t) : emptyToNull(t.substring(0, open).trim());
    }

    /** Field-by-field differences against another record, already normalised. Empty means equivalent. */
    List<String> diff(ImageMeta other) {
        List<String> out = new ArrayList<>();
        cmp(out, "format", normFormat(format), normFormat(other.format));
        cmp(out, "width", width, other.width);
        cmp(out, "height", height, other.height);
        cmp(out, "orientation", orientation, other.orientation);
        cmp(out, "make", normText(make), normText(other.make));
        cmp(out, "model", normText(model), normText(other.model));
        cmp(out, "gpsLongitude", normRational(gpsLongitude), normRational(other.gpsLongitude));
        cmp(out, "gpsLongitudeRef", normText(gpsLongitudeRef), normText(other.gpsLongitudeRef));
        cmp(out, "gpsLatitude", normRational(gpsLatitude), normRational(other.gpsLatitude));
        cmp(out, "gpsLatitudeRef", normText(gpsLatitudeRef), normText(other.gpsLatitudeRef));
        return out;
    }

    /** True when every EXIF-derived field is absent - i.e. the fixture never exercises the EXIF path. */
    boolean hasNoExif() {
        return orientation == null && normText(make) == null && normText(model) == null
            && normRational(gpsLatitude) == null && normRational(gpsLongitude) == null;
    }

    // ---- normalisation -----------------------------------------------------

    /** "JPEG" and "jpegload" both -> "jpeg". */
    private static String normFormat(String v) {
        String t = normText(v);
        if (t == null) return null;
        t = t.toLowerCase(Locale.ROOT);
        return t.endsWith("load") ? t.substring(0, t.length() - 4) : t;
    }

    /** GM's "48/1,13/1,2188/100" and libvips' "48/1 13/1 2188/100" -> one canonical spelling. */
    private static String normRational(String v) {
        String t = normText(v);
        return t == null ? null : t.replace(',', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String normText(String v) {
        return v == null ? null : emptyToNull(v.trim());
    }

    private static void cmp(List<String> out, String field, Object a, Object b) {
        if (!Objects.equals(a, b)) out.add(field + ": gm=" + show(a) + "  vips=" + show(b));
    }

    private static String show(Object o) { return o == null ? "<absent>" : "'" + o + "'"; }

    private static String emptyToNull(String v) { return (v == null || v.isEmpty()) ? null : v; }

    private static String at(String[] p, int i) { return i < p.length ? emptyToNull(p[i].trim()) : null; }

    private static Integer intAt(String[] p, int i) { return intOrNull(at(p, i)); }

    private static Integer intOrNull(String v) {
        if (v == null) return null;
        try { return Integer.valueOf(v.trim()); } catch (NumberFormatException e) { return null; }
    }
}

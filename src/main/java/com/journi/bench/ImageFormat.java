package com.journi.bench;

/**
 * Input/output format for a benchmark run, selected via BENCH_INPUT_FORMAT /
 * BENCH_OUTPUT_FORMAT. Drives file extensions, GM/IM CLI flags, and which
 * vips-ffm save method gets called.
 *
 * supportsAlpha=false (JPG) is what triggers flatten-to-white-background
 * compositing for alpha-channel sources - see VipsStreamingVariant's
 * flattenIfNeeded() and the GM/IM variants' format-conditional commands.
 */
public enum ImageFormat {
    JPG("jpg", true, false),
    PNG("png", false, true),
    WEBP("webp", true, true);

    public final String ext;
    public final boolean supportsQuality;
    public final boolean supportsAlpha;

    ImageFormat(String ext, boolean supportsQuality, boolean supportsAlpha) {
        this.ext = ext;
        this.supportsQuality = supportsQuality;
        this.supportsAlpha = supportsAlpha;
    }

    public static ImageFormat fromEnv(String value) {
        return switch (value.trim().toLowerCase()) {
            case "jpg", "jpeg" -> JPG;
            case "png" -> PNG;
            case "webp" -> WEBP;
            default -> throw new IllegalArgumentException(
                "Unknown image format: '" + value + "' (expected jpg, png, or webp)");
        };
    }
}

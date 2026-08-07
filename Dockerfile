# libvips-benchmark: GM/IM fetch-then-process vs. vips-ffm true streaming.
# Independent project - no dependency on the "core" repo's build, though the
# ImageMagick/GraphicsMagick install steps below are copied verbatim from
# core/Dockerfile for version parity with production.

# ---------- stage 1: native + production-parity tooling ----------
FROM eclipse-temurin:25-jdk-noble AS native
ENV DEBIAN_FRONTEND=noninteractive
ARG VIPS_VERSION=8.18.5
WORKDIR /app

# ImageMagick - copied verbatim from core/Dockerfile (policy.xml patches + version assert)
RUN apt-get update && \
    apt-get install -y imagemagick && \
    apt-get purge -y ghostscript && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    sed -i 's_<policy domain="resource" name="\([widthheight]*\)" value="[0-9]*[KP]*"/>_<policy domain="resource" name="\1" value="20KP"/>_' /etc/ImageMagick-6/policy.xml && \
    sed -i 's_<policy domain="resource" name="\([memoryarea]*\)" value="[0-9]*[MPMiB]*"/>_<policy domain="resource" name="\1" value="2GiB"/>_' /etc/ImageMagick-6/policy.xml && \
    convert --version | grep -i "ImageMagick" | head -n 1 | awk '{print $3}' | grep -q "6.9" || exit 1

# GraphicsMagick - copied verbatim from core/Dockerfile
RUN apt-get update && \
    apt-get install -y software-properties-common && \
    add-apt-repository -y ppa:ubuntuhandbook1/libheif && \
    apt-get update && \
    apt-get install -y webp libltdl-dev zlib1g-dev google-perftools libgoogle-perftools-dev libjpeg-dev libpng-dev \
            libheif1 libheif-plugin-libde265 libbz2-dev libdeflate-dev libheif-dev graphicsmagick && \
    rm -rf /var/lib/apt/lists/*

# libvips build deps + runtime tooling. Deliberately NOT installing libvips42/libvips-dev
# from apt (see the source build below) - vips-ffm dlopens libvips.so.42 via FFM, and a
# distro package's SIMD support is whatever Ubuntu happened to build, not something this
# benchmark controls or can verify from outside.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        build-essential meson ninja-build pkg-config wget ca-certificates \
        libglib2.0-0 procps \
        libhwy-dev libimagequant-dev \
        libglib2.0-dev libexpat1-dev libjpeg-turbo8-dev libpng-dev libspng-dev \
        libwebp-dev liblcms2-dev libexif-dev libtiff-dev && \
    rm -rf /var/lib/apt/lists/*

# jemalloc, installed but OFF by default - see entrypoint.sh, toggled via BENCH_ALLOCATOR=jemalloc.
# Not the default because it's a real deployment/allocator choice that changes what's being
# measured, not passive instrumentation - default stays glibc so every existing result stays
# comparable, and jemalloc is opt-in for A/B comparison.
RUN apt-get update && \
    apt-get install -y --no-install-recommends libjemalloc2 && \
    rm -rf /var/lib/apt/lists/* && \
    ldconfig -p | grep -q 'libjemalloc\.so\.2' || { echo "FATAL: libjemalloc2 did not install libjemalloc.so.2"; exit 1; }

# libvips from source (same version/procedure as Dockerfile.ffmbench, so the S3 harness and
# the local-resize harness run against byte-identical libvips builds).
#
# `-Dhighway=enabled` is NOT optional. meson AUTO-DETECTS Highway, so without libhwy-dev
# installed it silently produces a libvips reporting "SIMD support with libhwy or liborc:
# false" - scalar fallbacks for the vectorised resize/convolution kernels this benchmark
# spends all its time in. That understates libvips badly and makes results non-comparable
# with any SIMD-enabled build. `enabled` rather than the default `auto` turns a missing
# dependency into a BUILD failure instead of a quietly slow image. Same for spng (faster
# PNG decode than libpng) and imagequant (needed for pngsave palette=true).
WORKDIR /tmp
RUN wget -q https://github.com/libvips/libvips/releases/download/v${VIPS_VERSION}/vips-${VIPS_VERSION}.tar.xz && \
    tar xf vips-${VIPS_VERSION}.tar.xz && \
    cd vips-${VIPS_VERSION} && \
    meson setup build --buildtype=release -Dintrospection=disabled \
        -Dhighway=enabled -Dspng=enabled -Dimagequant=enabled && \
    ninja -C build && \
    ninja -C build install && \
    ldconfig && \
    cd /tmp && rm -rf vips-* && \
    vips --version && \
    ldconfig -p | grep -E 'libvips\.so\.42|libgobject-2\.0\.so\.0'

# Assert SIMD actually made it into the built library, and record the full build
# configuration in the build log as provenance for whatever the results claim.
RUN vips --vips-config && \
    { vips --vips-config | grep "^SIMD support" | grep -q ": true" || \
      { echo "FATAL: libvips built WITHOUT SIMD - benchmark results would understate libvips"; exit 1; }; }
WORKDIR /app

# ---------- stage 2: Maven build with cached dependency layer ----------
FROM native AS deps
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline || true

FROM deps AS builder
COPY src/ src/
RUN mvn -q -B package

# ---------- stage 3: final ----------
FROM native AS bench
WORKDIR /app
COPY --from=builder /app/target/bench.jar /app/bench.jar
COPY samples/ /app/samples/
COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Fail the BUILD, not a run against real S3, if vips-ffm cannot bind the source-built
# libvips (e.g. ldconfig missed it, or the JDK is too old for final FFM). Deliberately
# NOT through entrypoint.sh - this is a raw sanity check of the jar/native binding
# itself, independent of whichever allocator BENCH_ALLOCATOR ends up selecting at runtime.
RUN java --enable-native-access=ALL-UNNAMED -jar /app/bench.jar smoke-test

ENTRYPOINT ["/app/entrypoint.sh"]
CMD ["run"]

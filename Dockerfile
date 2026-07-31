# libvips-benchmark: GM/IM fetch-then-process vs. vips-ffm true streaming.
# Independent project - no dependency on the "core" repo's build, though the
# ImageMagick/GraphicsMagick install steps below are copied verbatim from
# core/Dockerfile for version parity with production.

# ---------- stage 1: native + production-parity tooling ----------
FROM eclipse-temurin:25-jdk-noble AS native
ENV DEBIAN_FRONTEND=noninteractive
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

# libvips: RUNTIME shared libs (what vips-ffm dlopens via FFM) + CLI for cross-checking/spikes.
# vips-ffm resolves libvips.so.42 / libglib-2.0.so.0 / libgobject-2.0.so.0 on the library path.
RUN apt-get update && \
    apt-get install -y --no-install-recommends libvips42 libvips-dev libvips-tools libglib2.0-0 procps && \
    rm -rf /var/lib/apt/lists/* && \
    vips --version && \
    ldconfig -p | grep -E 'libvips\.so\.42|libgobject-2\.0\.so\.0'

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
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-Xms2g", "-Xmx4g", "-jar", "/app/bench.jar"]
CMD ["run"]

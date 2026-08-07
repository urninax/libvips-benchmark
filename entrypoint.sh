#!/bin/sh
# Exec-form ENTRYPOINT can't branch on an env var, so this wrapper does the one
# thing it needs to: optionally set LD_PRELOAD before handing off to the JVM.
#
# LD_PRELOAD set HERE (PID 1) is inherited by every descendant - the vips
# `run-variant` child JVMs Main.java spawns AND every `gm`/`convert` subprocess
# they fork - so toggling the allocator is a single env var, no code changes
# needed anywhere else. Default (BENCH_ALLOCATOR unset or "glibc") changes
# nothing: today's default allocator behavior, byte-for-byte.
set -e

if [ "$BENCH_ALLOCATOR" = "jemalloc" ]; then
    JEMALLOC_PATH="$(ldconfig -p | grep 'libjemalloc\.so\.2' | awk '{print $NF}' | head -n1)"
    if [ -z "$JEMALLOC_PATH" ]; then
        echo "FATAL: BENCH_ALLOCATOR=jemalloc requested but libjemalloc.so.2 is not installed in this image" >&2
        exit 1
    fi
    export LD_PRELOAD="$JEMALLOC_PATH"
    echo "[entrypoint] BENCH_ALLOCATOR=jemalloc - LD_PRELOAD=$LD_PRELOAD"
else
    echo "[entrypoint] BENCH_ALLOCATOR=${BENCH_ALLOCATOR:-glibc} (default allocator, no LD_PRELOAD)"
fi

exec java --enable-native-access=ALL-UNNAMED -Xms2g -Xmx4g -jar /app/bench.jar "$@"

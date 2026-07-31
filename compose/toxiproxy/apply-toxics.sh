#!/bin/sh
set -eu

# Creates (or resets) the "s3" toxiproxy proxy pointing at minio:9000, then
# applies the latency+bandwidth toxics for LATENCY_PROFILE, via Toxiproxy's
# plain HTTP API (curl) rather than toxiproxy-cli - the toxiproxy image itself
# is a minimal scratch-based binary with no shell, so this script runs from a
# separate curl+shell image instead (see docker-compose.yml's `toxics` service).
#
# Toxiproxy doesn't model TCP congestion-window growth - treat these as
# directionally correct for the streaming-vs-buffered TREND across latency,
# not as precise real-S3 predictions.

BASE="${TOXIPROXY_URL:-http://toxiproxy:8474}"

# idempotent: delete if it already exists from a previous profile switch
curl -sf -X DELETE "$BASE/proxies/s3" >/dev/null 2>&1 || true

curl -sf -X POST "$BASE/proxies" \
  -H 'Content-Type: application/json' \
  -d '{"name":"s3","listen":"0.0.0.0:9000","upstream":"minio:9000"}' >/dev/null

add_toxic() {
  name="$1"; type="$2"; attributes="$3"
  curl -sf -X POST "$BASE/proxies/s3/toxics" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"$name\",\"type\":\"$type\",\"attributes\":$attributes}" >/dev/null
}

case "${LATENCY_PROFILE:-none}" in
  none)
    echo "LATENCY_PROFILE=none: proxy pass-through, no toxics applied."
    ;;
  az)
    # same-region S3 approximation
    add_toxic latency_down latency '{"latency":1,"jitter":0}'
    add_toxic bandwidth_down bandwidth '{"rate":125000}'
    ;;
  region)
    # cross-region approximation
    add_toxic latency_down latency '{"latency":40,"jitter":5}'
    add_toxic bandwidth_down bandwidth '{"rate":25000}'
    ;;
  degraded)
    add_toxic latency_down latency '{"latency":120,"jitter":25}'
    add_toxic bandwidth_down bandwidth '{"rate":5000}'
    ;;
  *)
    echo "Unknown LATENCY_PROFILE: ${LATENCY_PROFILE}" >&2
    exit 1
    ;;
esac

echo "Applied latency profile: ${LATENCY_PROFILE:-none}"
curl -sf "$BASE/proxies/s3" | head -c 500
echo

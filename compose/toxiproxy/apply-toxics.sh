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

# Toxiproxy's bandwidth "rate" is in KB/s, and the toxic is applied PER
# CONNECTION - so these model one worker's share of the pipe, not the whole
# instance's aggregate. Multiply by 8/1000 for Mbit/s.
#
# The s3* profiles below are the decision-relevant ones: they bracket what an
# EC2 host actually sees talking to S3. AWS's own guidance is ~85-90 MB/s per
# GET connection; in practice the ceiling is usually the instance's network
# allocation split across concurrent workers, not S3 itself.
#
# The az/region/degraded profiles are kept for continuity with earlier runs,
# but note what they actually are in real terms: 10 Mbit/s, 2 Mbit/s and
# 0.4 Mbit/s links - i.e. home broadband, bad DSL, and dial-up. They are 20-70x
# slower than real S3 and will overstate any streaming benefit, because
# streaming can only ever hide min(fetch, compute) and those rates make fetch
# enormous.
case "${LATENCY_PROFILE:-none}" in
  none)
    echo "LATENCY_PROFILE=none: proxy pass-through, no toxics applied."
    ;;
  s3fast)
    # EC2 -> S3, same region, warm connection, uncontended.
    # ~85 MB/s (680 Mbit/s); 3.3MB source fetches in ~40ms + latency.
    add_toxic latency_down latency '{"latency":25,"jitter":5}'
    add_toxic bandwidth_down bandwidth '{"rate":85000}'
    ;;
  s3real)
    # EC2 -> S3, same region, but 16 workers sharing the instance NIC.
    # ~30 MB/s each (240 Mbit/s); 3.3MB fetches in ~110ms + latency.
    # This is the profile most likely to match Journi's real deployment.
    add_toxic latency_down latency '{"latency":30,"jitter":10}'
    add_toxic bandwidth_down bandwidth '{"rate":30000}'
    ;;
  s3cross)
    # Cross-region S3. RTT dominates first-byte; single-stream throughput
    # drops with the bandwidth-delay product. ~12 MB/s; ~275ms + latency.
    add_toxic latency_down latency '{"latency":95,"jitter":15}'
    add_toxic bandwidth_down bandwidth '{"rate":12000}'
    ;;
  az)
    # NOT same-region S3 despite the name - this is a 10 Mbit/s link.
    add_toxic latency_down latency '{"latency":1,"jitter":0}'
    add_toxic bandwidth_down bandwidth '{"rate":1250}'
    ;;
  region)
    # 2 Mbit/s link.
    add_toxic latency_down latency '{"latency":40,"jitter":5}'
    add_toxic bandwidth_down bandwidth '{"rate":250}'
    ;;
  degraded)
    # 0.4 Mbit/s link. NOTE: at this rate a 3.3MB fetch takes ~66s, which
    # exceeds S3_TIMEOUT_SECONDS=30 and makes the GM/IM variants fail every
    # job with "Content input stream does not support mark/reset". Raise
    # S3_TIMEOUT_SECONDS before trusting GM/IM numbers from this profile.
    add_toxic latency_down latency '{"latency":120,"jitter":25}'
    add_toxic bandwidth_down bandwidth '{"rate":50}'
    ;;
  *)
    echo "Unknown LATENCY_PROFILE: ${LATENCY_PROFILE}" >&2
    exit 1
    ;;
esac

echo "Applied latency profile: ${LATENCY_PROFILE:-none}"
curl -sf "$BASE/proxies/s3" | head -c 500
echo

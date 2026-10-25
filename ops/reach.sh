#!/usr/bin/env bash
# End-to-end check of the remote path from this machine.
#   CCTV_AUTH=admin:password ops/reach.sh <phone-tailnet-ip>
set -u
HOST="${1:?usage: reach.sh <phone-ip>}"; AUTH="${CCTV_AUTH:?set CCTV_AUTH=user:password}"
echo "== health";    curl -s -m 10 -u "$AUTH" "http://$HOST:8080/api/health" | head -c 400; echo
echo "== rtsp";      ffprobe -v error -rtsp_transport tcp -timeout 15000000 -select_streams v -show_entries stream=codec_name,width,height -of csv=p=0 "rtsp://$HOST:8554/stream"
echo "== adb";       adb connect "$HOST:5555" && adb -s "$HOST:5555" shell echo ok
echo "== shot";      curl -s -m 10 -u "$AUTH" -D - -o /tmp/cctv_reach.jpg "http://$HOST:8080/shot.jpg" | grep -iE '^HTTP|x-frame-age|etag'; ls -la /tmp/cctv_reach.jpg

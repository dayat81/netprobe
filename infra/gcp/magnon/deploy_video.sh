#!/usr/bin/env bash
# Publish the 360/720/1080 ladder the same way Netprobe does.
# The APK requests https://magnon.site/video/<name>.mp4 with no credentials.
set -euo pipefail
PROJECT="${PROJECT:-magnon-app-dayat81}"
REGION="${REGION:-asia-southeast2}"
BUCKET="${PROJECT}-magnon-video"
SOURCE="${VIDEO_SOURCE:-https://prod.netprobe.xyz/video}"
STAGE="$(mktemp -d)"
trap 'rm -rf "${STAGE}"' EXIT

gcloud storage buckets describe "gs://${BUCKET}" --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud storage buckets create "gs://${BUCKET}" --location "${REGION}" --project "${PROJECT}"
gcloud storage buckets update "gs://${BUCKET}" --project "${PROJECT}" --no-public-access-prevention
gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" \
  --project "${PROJECT}" \
  --member=allUsers \
  --role=roles/storage.objectViewer >/dev/null

if [[ -d "${VIDEO_DIR:-}" ]]; then
  gcloud storage cp "${VIDEO_DIR}/"*.mp4 "gs://${BUCKET}/video/" --project "${PROJECT}"
else
  for f in 360.mp4 720.mp4 1080.mp4; do
    curl -fsS --max-time 120 -o "${STAGE}/${f}" "${SOURCE}/${f}"
    echo "fetched ${f} $(wc -c < "${STAGE}/${f}")"
  done
  gcloud storage cp "${STAGE}/"*.mp4 "gs://${BUCKET}/video/" --project "${PROJECT}"
fi

gcloud compute url-maps invalidate-cdn-cache magnon \
  --project "${PROJECT}" --global --path "/video/*" --async || true
echo VIDEO_OK

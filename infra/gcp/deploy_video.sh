#!/usr/bin/env bash
# Bucket for the video ladder. CDN + dual-stack LB is attached when the domain is cut over.
set -euo pipefail
source "$(dirname "$0")/env.sh"
BUCKET="${PROJECT}-netprobe-video"
gcloud storage buckets describe "gs://${BUCKET}" --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud storage buckets create "gs://${BUCKET}" --location "${REGION}" --project "${PROJECT}"
# The APK fetches these clips with no credentials. A private bucket returns 403.
gcloud storage buckets update "gs://${BUCKET}" --project "${PROJECT}" --no-public-access-prevention
gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" \
  --project "${PROJECT}" \
  --member=allUsers \
  --role=roles/storage.objectViewer >/dev/null
echo "bucket gs://${BUCKET}"
if [[ -d "${VIDEO_DIR:-}" ]]; then
  gcloud storage cp "${VIDEO_DIR}/"*.mp4 "gs://${BUCKET}/video/"
fi

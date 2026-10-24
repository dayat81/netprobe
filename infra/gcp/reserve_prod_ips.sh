#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(gcloud config get-value project)"
gcloud compute addresses describe netprobe-prod-v4 --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute addresses create netprobe-prod-v4 --global --ip-version=IPV4 --project "${PROJECT}"
gcloud compute addresses describe netprobe-prod-v6 --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute addresses create netprobe-prod-v6 --global --ip-version=IPV6 --project "${PROJECT}"
echo "V4=$(gcloud compute addresses describe netprobe-prod-v4 --global --project "${PROJECT}" --format='value(address)')"
echo "V6=$(gcloud compute addresses describe netprobe-prod-v6 --global --project "${PROJECT}" --format='value(address)')"

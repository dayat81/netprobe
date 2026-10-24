#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
IMAGE="${AR}/speedtest:prod"
cd "${REPO_ROOT}"
gcloud builds submit --project "${PROJECT}" --tag "${IMAGE}" -f server/Dockerfile.speedtest .
gcloud run deploy netprobe-speedtest \
  --project "${PROJECT}" \
  --region "${REGION}" \
  --image "${IMAGE}" \
  --allow-unauthenticated \
  --min-instances 1 \
  --max-instances 10 \
  --memory 2Gi \
  --cpu 2 \
  --timeout 300 \
  --no-cpu-throttling \
  --set-env-vars "SPEEDTEST_MAX_BODY=16777216,SPEEDTEST_SERVER_NAME=netprobe-gcp,SPEEDTEST_REGION=asia-southeast2"
gcloud run services describe netprobe-speedtest --region "${REGION}" --project "${PROJECT}" --format='value(status.url)'

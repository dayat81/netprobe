#!/usr/bin/env bash
set -euo pipefail
PROJECT="${PROJECT:-magnon-app-dayat81}"
REGION="${REGION:-asia-southeast2}"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/magnon/speedtest:prod"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "${ROOT}"
gcloud builds submit --project "${PROJECT}" --config infra/gcp/cloudbuild-speedtest.yaml --substitutions "_IMAGE=${IMAGE}" .
gcloud run deploy magnon-speedtest \
  --project "${PROJECT}" \
  --region "${REGION}" \
  --image "${IMAGE}" \
  --allow-unauthenticated \
  --min-instances 0 \
  --max-instances 10 \
  --memory 2Gi \
  --cpu 2 \
  --timeout 300 \
  --no-cpu-throttling \
  --set-env-vars "SPEEDTEST_MAX_BODY=16777216,SPEEDTEST_SERVER_NAME=magnon,SPEEDTEST_REGION=asia-southeast2"
gcloud run services describe magnon-speedtest --region "${REGION}" --project "${PROJECT}" --format='value(status.url)'

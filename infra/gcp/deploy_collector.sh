#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
IMAGE="${AR}/collector:prod"
cd "${REPO_ROOT}"
gcloud builds submit --project "${PROJECT}" --tag "${IMAGE}" -f server/Dockerfile.collector .
gcloud run deploy netprobe-collector \
  --project "${PROJECT}" \
  --region "${REGION}" \
  --image "${IMAGE}" \
  --allow-unauthenticated \
  --min-instances 1 \
  --max-instances 20 \
  --memory 1Gi \
  --cpu 1 \
  --timeout 60 \
  --no-cpu-throttling \
  --set-secrets "/secrets/hmac.key=netprobe-hmac:latest" \
  --set-env-vars "OSM_STORE=firestore,OSM_ROLE=flows,OSM_TTL_SECONDS=7776000"
gcloud run services describe netprobe-collector --region "${REGION}" --project "${PROJECT}" --format='value(status.url)'

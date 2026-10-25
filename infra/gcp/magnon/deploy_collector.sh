#!/usr/bin/env bash
set -euo pipefail
PROJECT="${PROJECT:-magnon-app-dayat81}"
REGION="${REGION:-asia-southeast2}"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/magnon/collector:prod"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "${ROOT}"
gcloud builds submit --project "${PROJECT}" --config infra/gcp/cloudbuild-collector.yaml --substitutions "_IMAGE=${IMAGE}" .
gcloud run deploy magnon-collector \
  --project "${PROJECT}" \
  --region "${REGION}" \
  --image "${IMAGE}" \
  --allow-unauthenticated \
  --min-instances 1 \
  --max-instances 10 \
  --memory 1Gi \
  --cpu 1 \
  --timeout 60 \
  --no-cpu-throttling \
  --set-secrets "/secrets/hmac.key=magnon-hmac:latest,MAGNON_XAI_API_KEY=magnon-xai:latest,TYPESAFE_API_KEY=magnon-typesafe:latest,MAGNON_DEEPSEEK_API_KEY=magnon-deepseek:latest,MAGNON_GMAP_API_KEY=magnon-gmap:latest" \
  --set-env-vars "OSM_STORE=firestore,OSM_ROLE=flows,OSM_TTL_SECONDS=7776000,MAGNON_PORTAL=1,MAGNON_CREDITS=0,MAGNON_GOOGLE_CLIENT_ID=${MAGNON_GOOGLE_CLIENT_ID:-},MAGNON_OPERATOR_EMAILS=${MAGNON_OPERATOR_EMAILS:-},MAGNON_ADMIN_EMAILS=${MAGNON_ADMIN_EMAILS:-}"
gcloud run services describe magnon-collector --region "${REGION}" --project "${PROJECT}" --format='value(status.url)'

#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
NUM="$(gcloud projects describe "${PROJECT}" --format='value(projectNumber)')"
SA="${NUM}-compute@developer.gserviceaccount.com"
gcloud secrets add-iam-policy-binding netprobe-hmac \
  --project "${PROJECT}" \
  --member "serviceAccount:${SA}" \
  --role roles/secretmanager.secretAccessor --quiet >/dev/null
IMAGE="${AR}/collector:prod"
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
echo DEPLOY_DONE

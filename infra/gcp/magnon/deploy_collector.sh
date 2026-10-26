#!/usr/bin/env bash
set -euo pipefail
PROJECT="${PROJECT:-magnon-app-dayat81}"
REGION="${REGION:-asia-southeast2}"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT}/magnon/collector:prod"
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "${ROOT}"
PCAP_BUCKET="${PROJECT}-magnon-pcap"
if ! gcloud storage buckets describe "gs://${PCAP_BUCKET}" --project "${PROJECT}" >/dev/null 2>&1; then
  gcloud storage buckets create "gs://${PCAP_BUCKET}" \
    --project "${PROJECT}" \
    --location "${REGION}" \
    --uniform-bucket-level-access \
    --public-access-prevention
fi
RUNTIME="$(gcloud run services describe magnon-collector --region "${REGION}" --project "${PROJECT}" --format='value(spec.template.spec.serviceAccountName)' 2>/dev/null || true)"
if [[ -z "${RUNTIME}" ]]; then
  PROJECT_NUMBER="$(gcloud projects describe "${PROJECT}" --format='value(projectNumber)')"
  RUNTIME="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
fi
echo "pcap_runtime=${RUNTIME}"
gcloud storage buckets add-iam-policy-binding "gs://${PCAP_BUCKET}" \
  --project "${PROJECT}" \
  --member="serviceAccount:${RUNTIME}" \
  --role="roles/storage.objectAdmin" \
  --quiet >/dev/null
gcloud iam service-accounts add-iam-policy-binding "${RUNTIME}" \
  --project "${PROJECT}" \
  --member="serviceAccount:${RUNTIME}" \
  --role="roles/iam.serviceAccountTokenCreator" \
  --quiet >/dev/null
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
  --set-env-vars "OSM_STORE=firestore,OSM_ROLE=flows,OSM_TTL_SECONDS=7776000,MAGNON_PORTAL=1,MAGNON_CREDITS=0,MAGNON_PCAP_BUCKET=${PROJECT}-magnon-pcap,MAGNON_GOOGLE_CLIENT_ID=${MAGNON_GOOGLE_CLIENT_ID:-},MAGNON_OPERATOR_EMAILS=${MAGNON_OPERATOR_EMAILS:-},MAGNON_ADMIN_EMAILS=${MAGNON_ADMIN_EMAILS:-}"
gcloud run services describe magnon-collector --region "${REGION}" --project "${PROJECT}" --format='value(status.url)'

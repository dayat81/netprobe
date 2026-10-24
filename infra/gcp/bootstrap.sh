#!/usr/bin/env bash
# Enable APIs, Artifact Registry, Firestore, BigQuery, and the HMAC secret.
set -euo pipefail
source "$(dirname "$0")/env.sh"

echo "project=${PROJECT} region=${REGION}"
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  firestore.googleapis.com \
  bigquery.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  compute.googleapis.com \
  --project "${PROJECT}"

gcloud artifacts repositories describe netprobe --location "${REGION}" --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud artifacts repositories create netprobe \
       --repository-format=docker \
       --location "${REGION}" \
       --project "${PROJECT}"

gcloud firestore databases describe --database="(default)" --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud firestore databases create \
       --location="${REGION}" \
       --type=firestore-native \
       --project "${PROJECT}"

bq --project_id="${PROJECT}" show "${PROJECT}:netprobe" >/dev/null 2>&1 \
  || bq --project_id="${PROJECT}" mk --dataset --location="${REGION}" "${PROJECT}:netprobe"

if ! gcloud secrets describe netprobe-hmac --project "${PROJECT}" >/dev/null 2>&1; then
  key_file="$(mktemp)"
  python3 - <<PY >"${key_file}"
import secrets
print("key_id=netprobe-prod")
print("key_hex=%s" % secrets.token_hex(32))
print("measure_key_hex=%s" % secrets.token_hex(32))
PY
  gcloud secrets create netprobe-hmac --data-file="${key_file}" --project "${PROJECT}"
  rm -f "${key_file}"
  echo "created secret netprobe-hmac (new keys; bake them into a prod APK)"
else
  echo "secret netprobe-hmac already exists"
fi

echo "bootstrap ok"

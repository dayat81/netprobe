#!/usr/bin/env bash
# Magnon project: serverless stack matching Netprobe, plus static addresses and a managed cert.
set -euo pipefail
# magnon-app is already taken globally, so this project uses a unique id.
PROJECT="${PROJECT:-magnon-app-dayat81}"
REGION="${REGION:-asia-southeast2}"
BILLING="${BILLING:-0171BE-B733F2-41596B}"
NAME="${NAME:-Magnon}"

if ! gcloud projects describe "${PROJECT}" >/dev/null 2>&1; then
  gcloud projects create "${PROJECT}" --name="${NAME}"
fi
gcloud billing projects link "${PROJECT}" --billing-account="${BILLING}" >/dev/null

gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  firestore.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  compute.googleapis.com \
  iam.googleapis.com \
  --project "${PROJECT}"

gcloud artifacts repositories describe magnon --location "${REGION}" --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud artifacts repositories create magnon \
       --repository-format=docker \
       --location "${REGION}" \
       --project "${PROJECT}"

if ! gcloud firestore databases describe --database="(default)" --project "${PROJECT}" >/dev/null 2>&1; then
  gcloud firestore databases create \
    --location="${REGION}" \
    --type=firestore-native \
    --project "${PROJECT}"
fi

NUM="$(gcloud projects describe "${PROJECT}" --format='value(projectNumber)')"
RUN_SA="${NUM}-compute@developer.gserviceaccount.com"
CB_SA="${NUM}@cloudbuild.gserviceaccount.com"
bind_role() {
  local member="$1" role="$2" attempt
  for attempt in 1 2 3 4 5; do
    if gcloud projects add-iam-policy-binding "${PROJECT}" \
      --member="serviceAccount:${member}" \
      --role="${role}" --quiet >/dev/null; then
      return 0
    fi
    sleep $((attempt * 2))
  done
  return 1
}
for member in "${RUN_SA}" "${CB_SA}"; do
  bind_role "${member}" roles/artifactregistry.write
  bind_role "${member}" roles/storage.admin
done
bind_role "${RUN_SA}" roles/datastore.use
bind_role "${RUN_SA}" roles/secretmanager.secretAccesso

SECRET_FILE="${SECRET_FILE:-/mnt/d/netprobe/tmp/magnon-hmac.key}"
if ! gcloud secrets describe magnon-hmac --project "${PROJECT}" >/dev/null 2>&1; then
  mkdir -p "$(dirname "${SECRET_FILE}")"
  python3 - <<PY >"${SECRET_FILE}"
import secrets
print("key_id=magnon-prod")
print("key_hex=%s" % secrets.token_hex(32))
print("measure_key_hex=%s" % secrets.token_hex(32))
PY
  chmod 600 "${SECRET_FILE}"
  gcloud secrets create magnon-hmac --data-file="${SECRET_FILE}" --project "${PROJECT}"
  echo "created secret magnon-hmac"
else
  echo "secret magnon-hmac already exists"
fi

gcloud compute addresses describe magnon-v4 --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute addresses create magnon-v4 --global --ip-version=IPV4 --project "${PROJECT}"
gcloud compute addresses describe magnon-v6 --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute addresses create magnon-v6 --global --ip-version=IPV6 --project "${PROJECT}"

gcloud compute ssl-certificates describe magnon-cert --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute ssl-certificates create magnon-cert \
       --domains=magnon.site --global --project "${PROJECT}"

echo "PROJECT=${PROJECT}"
echo "NUMBER=${NUM}"
echo "V4=$(gcloud compute addresses describe magnon-v4 --global --project "${PROJECT}" --format='value(address)')"
echo "V6=$(gcloud compute addresses describe magnon-v6 --global --project "${PROJECT}" --format='value(address)')"
gcloud compute ssl-certificates describe magnon-cert --global --project "${PROJECT}" --format='value(managed.status,managed.domainStatus)'
echo "BOOTSTRAP_OK"

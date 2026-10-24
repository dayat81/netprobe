#!/usr/bin/env bash
set -euo pipefail
PROJECT="$(gcloud config get-value project)"
NUM="$(gcloud projects describe "${PROJECT}" --format='value(projectNumber)')"
SA="${NUM}-compute@developer.gserviceaccount.com"
CB="${NUM}@cloudbuild.gserviceaccount.com"
echo "sa=${SA}"
echo "cb=${CB}"
for member in "${SA}" "${CB}"; do
  gcloud projects add-iam-policy-binding "${PROJECT}" \
    --member="serviceAccount:${member}" \
    --role="roles/storage.admin" --quiet >/dev/null
  gcloud projects add-iam-policy-binding "${PROJECT}" \
    --member="serviceAccount:${member}" \
    --role="roles/artifactregistry.writer" --quiet >/dev/null
done
echo IAM_OK

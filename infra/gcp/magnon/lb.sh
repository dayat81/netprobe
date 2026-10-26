#!/usr/bin/env bash
# HTTPS load balancer for magnon.site. Addresses and the managed cert come from bootstrap.sh.
set -euo pipefail
PROJECT="${PROJECT:-magnon-app-dayat81}"
REGION="${REGION:-asia-southeast2}"
BUCKET="${PROJECT}-magnon-video"
LITE_BUCKET="${PROJECT}-magnon-lite"
CERT=magnon-cert
MAP=magnon
PROXY=magnon-https

gcloud storage buckets describe "gs://${BUCKET}" --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud storage buckets create "gs://${BUCKET}" --location "${REGION}" --project "${PROJECT}"
# The APK fetches these clips with no credentials. A private bucket returns 403.
gcloud storage buckets update "gs://${BUCKET}" --project "${PROJECT}" --no-public-access-prevention
gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" \
  --project "${PROJECT}" \
  --member=allUsers \
  --role=roles/storage.objectViewer >/dev/null

ensure_neg() {
  local name="$1" service="$2"
  gcloud compute network-endpoint-groups describe "${name}" --region "${REGION}" --project "${PROJECT}" >/dev/null 2>&1 \
    || gcloud compute network-endpoint-groups create "${name}" \
         --region "${REGION}" --project "${PROJECT}" \
         --network-endpoint-type=serverless \
         --cloud-run-service="${service}"
}
ensure_bes() {
  local name="$1" neg="$2"
  gcloud compute backend-services describe "${name}" --global --project "${PROJECT}" >/dev/null 2>&1 \
    || gcloud compute backend-services create "${name}" \
         --global --project "${PROJECT}" \
         --load-balancing-scheme=EXTERNAL_MANAGED
  if ! gcloud compute backend-services describe "${name}" --global --project "${PROJECT}" --format='value(backends[].group)' | grep -q "${neg}"; then
    gcloud compute backend-services add-backend "${name}" \
      --global --project "${PROJECT}" \
      --network-endpoint-group="${neg}" \
      --network-endpoint-group-region="${REGION}"
  fi
}

ensure_neg magnon-collector-neg magnon-collector
ensure_neg magnon-speedtest-neg magnon-speedtest
ensure_bes magnon-collector-bes magnon-collector-neg
ensure_bes magnon-speedtest-bes magnon-speedtest-neg

gcloud compute backend-buckets describe magnon-video-bb --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute backend-buckets create magnon-video-bb \
       --gcs-bucket-name="${BUCKET}" --enable-cdn --project "${PROJECT}"

# Lite release APK. Replacing the object does not redeploy Cloud Run.
gcloud storage buckets describe "gs://${LITE_BUCKET}" --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud storage buckets create "gs://${LITE_BUCKET}" --location "${REGION}" --project "${PROJECT}"
gcloud storage buckets update "gs://${LITE_BUCKET}" --project "${PROJECT}" --no-public-access-prevention
gcloud storage buckets add-iam-policy-binding "gs://${LITE_BUCKET}" \
  --project "${PROJECT}" \
  --member=allUsers \
  --role=roles/storage.objectViewer >/dev/null
gcloud compute backend-buckets describe magnon-lite-bb --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute backend-buckets create magnon-lite-bb \
       --gcs-bucket-name="${LITE_BUCKET}" --enable-cdn --project "${PROJECT}"

gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute url-maps create "${MAP}" \
       --default-service=magnon-collector-bes --global --project "${PROJECT}"

if ! gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" --format='value(pathMatchers[].name)' | grep -q site; then
  gcloud compute url-maps add-path-matcher "${MAP}" \
    --path-matcher-name=site \
    --default-service=magnon-collector-bes \
    --backend-service-path-rules="/v1/*=magnon-collector-bes,/api/*=magnon-collector-bes,/speedtest/*=magnon-speedtest-bes" \
    --backend-bucket-path-rules="/video/*=magnon-video-bb,/magnon-lite.apk=magnon-lite-bb" \
    --global --project "${PROJECT}"
fi
if ! gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" --format='value(hostRules[].hosts[])' | grep -q magnon.site; then
  gcloud compute url-maps add-host-rule "${MAP}" \
    --hosts=magnon.site \
    --path-matcher-name=site \
    --global --project "${PROJECT}"
fi

gcloud compute target-https-proxies describe "${PROXY}" --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute target-https-proxies create "${PROXY}" \
       --ssl-certificates="${CERT}" --url-map="${MAP}" --global --project "${PROJECT}"

gcloud compute forwarding-rules describe magnon-https-v4 --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute forwarding-rules create magnon-https-v4 \
       --global --project "${PROJECT}" \
       --load-balancing-scheme=EXTERNAL_MANAGED \
       --target-https-proxy="${PROXY}" \
       --address=magnon-v4 \
       --ports=443
gcloud compute forwarding-rules describe magnon-https-v6 --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute forwarding-rules create magnon-https-v6 \
       --global --project "${PROJECT}" \
       --load-balancing-scheme=EXTERNAL_MANAGED \
       --target-https-proxy="${PROXY}" \
       --address=magnon-v6 \
       --ports=443

# add-path-matcher does not update a matcher that already exists.
if ! gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" --format='value(pathMatchers[].pathRules[].paths[])' | grep -q '/magnon-lite.apk'; then
  MAP_FILE="$(mktemp)"
  gcloud compute url-maps export "${MAP}" --destination="${MAP_FILE}" --global --project "${PROJECT}"
  python3 - "${MAP_FILE}" "${PROJECT}" <<'PY'
import sys
from pathlib import Path
path, project = sys.argv[1:]
file = Path(path)
text = file.read_text(encoding="utf-8")
if "/magnon-lite.apk" in text:
    raise SystemExit(0)
needle = (
    "  - paths:\n"
    "    - /video/*\n"
    f"    service: https://www.googleapis.com/compute/v1/projects/{project}/global/backendBuckets/magnon-video-bb\n"
)
insert = needle + (
    "  - paths:\n"
    "    - /magnon-lite.apk\n"
    f"    service: https://www.googleapis.com/compute/v1/projects/{project}/global/backendBuckets/magnon-lite-bb\n"
)
if needle not in text:
    raise SystemExit("video path rule missing; refusing to rewrite the url map")
file.write_text(text.replace(needle, insert, 1), encoding="utf-8")
PY
  gcloud compute url-maps import "${MAP}" --source="${MAP_FILE}" --global --project "${PROJECT}" --quiet
  rm -f "${MAP_FILE}"
fi

# Homepage and the Premium release live in the same bucket. Replacing an object does not redeploy Cloud Run.
gcloud storage buckets update "gs://${LITE_BUCKET}" --project "${PROJECT}" --web-main-page-suffix=index.html
if ! gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" --format='value(pathMatchers[].pathRules[].paths[])' | grep -q '/index.html'; then
  MAP_FILE="$(mktemp)"
  gcloud compute url-maps export "${MAP}" --destination="${MAP_FILE}" --global --project "${PROJECT}"
  python3 - "${MAP_FILE}" "${PROJECT}" <<'PY'
import sys
from pathlib import Path
path, project = sys.argv[1:]
file = Path(path)
text = file.read_text(encoding="utf-8")
service = f"https://www.googleapis.com/compute/v1/projects/{project}/global/backendBuckets/magnon-lite-bb"
needle = (
    "  - paths:\n"
    "    - /magnon-lite.apk\n"
    f"    service: {service}\n"
)
extra = ""
if "/index.html" not in text:
    extra += (
        "  - paths:\n"
        "    - /\n"
        "    - /index.html\n"
        f"    service: {service}\n"
    )
if "/magnon-premium.apk" not in text:
    extra += (
        "  - paths:\n"
        "    - /magnon-premium.apk\n"
        f"    service: {service}\n"
    )
if not extra:
    raise SystemExit(0)
if needle not in text:
    raise SystemExit("lite apk path rule missing; refusing to rewrite the url map")
file.write_text(text.replace(needle, needle + extra, 1), encoding="utf-8")
PY
  gcloud compute url-maps import "${MAP}" --source="${MAP_FILE}" --global --project "${PROJECT}" --quiet
  rm -f "${MAP_FILE}"
fi

gcloud compute ssl-certificates describe "${CERT}" --global --project "${PROJECT}" --format='value(managed.status,managed.domainStatus)'
echo LB_OK

#!/usr/bin/env bash
# HTTPS load balancer on the reserved prod addresses.
# /v1 -> collector, /speedtest -> speedtest, /video -> GCS bucket.
set -euo pipefail
source "$(dirname "$0")/env.sh"
PROJECT="${PROJECT}"
REGION="${REGION}"
BUCKET="${PROJECT}-netprobe-video"
CERT=netprobe-prod-cert
MAP=netprobe-prod
PROXY=netprobe-prod-https

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

ensure_neg netprobe-collector-neg netprobe-collector
ensure_neg netprobe-speedtest-neg netprobe-speedtest
ensure_bes netprobe-collector-bes netprobe-collector-neg
ensure_bes netprobe-speedtest-bes netprobe-speedtest-neg

gcloud compute backend-buckets describe netprobe-video-bb --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute backend-buckets create netprobe-video-bb \
       --gcs-bucket-name="${BUCKET}" --enable-cdn --project "${PROJECT}"

gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute url-maps create "${MAP}" \
       --default-service=netprobe-collector-bes --global --project "${PROJECT}"

if ! gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" --format='value(pathMatchers[].name)' | grep -q prod; then
  gcloud compute url-maps add-path-matcher "${MAP}" \
    --path-matcher-name=prod \
    --default-service=netprobe-collector-bes \
    --backend-service-path-rules="/v1/*=netprobe-collector-bes,/speedtest/*=netprobe-speedtest-bes" \
    --backend-bucket-path-rules="/video/*=netprobe-video-bb" \
    --global --project "${PROJECT}"
fi
if ! gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" --format='value(hostRules[].hosts[])' | grep -q prod.netprobe.xyz; then
  gcloud compute url-maps add-host-rule "${MAP}" \
    --hosts=prod.netprobe.xyz \
    --path-matcher-name=prod \
    --global --project "${PROJECT}"
fi

gcloud compute ssl-certificates describe "${CERT}" --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute ssl-certificates create "${CERT}" \
       --domains=prod.netprobe.xyz --global --project "${PROJECT}"

gcloud compute target-https-proxies describe "${PROXY}" --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute target-https-proxies create "${PROXY}" \
       --ssl-certificates="${CERT}" --url-map="${MAP}" --global --project "${PROJECT}"

gcloud compute forwarding-rules describe netprobe-prod-https-v4 --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute forwarding-rules create netprobe-prod-https-v4 \
       --global --project "${PROJECT}" \
       --load-balancing-scheme=EXTERNAL_MANAGED \
       --target-https-proxy="${PROXY}" \
       --address=netprobe-prod-v4 \
       --ports=443
gcloud compute forwarding-rules describe netprobe-prod-https-v6 --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute forwarding-rules create netprobe-prod-https-v6 \
       --global --project "${PROJECT}" \
       --load-balancing-scheme=EXTERNAL_MANAGED \
       --target-https-proxy="${PROXY}" \
       --address=netprobe-prod-v6 \
       --ports=443

gcloud compute ssl-certificates describe "${CERT}" --global --project "${PROJECT}" --format='value(managed.status,managed.domainStatus)'
echo LB_OK

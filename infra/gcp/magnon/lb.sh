#!/usr/bin/env bash
# HTTPS load balancer for magnon.site. Addresses and the managed cert come from bootstrap.sh.
set -euo pipefail
PROJECT="${PROJECT:-magnon-app-dayat81}"
REGION="${REGION:-asia-southeast2}"
BUCKET="${PROJECT}-magnon-video"
CERT=magnon-cert
MAP=magnon
PROXY=magnon-https

gcloud storage buckets describe "gs://${BUCKET}" --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud storage buckets create "gs://${BUCKET}" --location "${REGION}" --project "${PROJECT}"

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

gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" >/dev/null 2>&1 \
  || gcloud compute url-maps create "${MAP}" \
       --default-service=magnon-collector-bes --global --project "${PROJECT}"

if ! gcloud compute url-maps describe "${MAP}" --global --project "${PROJECT}" --format='value(pathMatchers[].name)' | grep -q site; then
  gcloud compute url-maps add-path-matcher "${MAP}" \
    --path-matcher-name=site \
    --default-service=magnon-collector-bes \
    --backend-service-path-rules="/v1/*=magnon-collector-bes,/api/*=magnon-collector-bes,/speedtest/*=magnon-speedtest-bes" \
    --backend-bucket-path-rules="/video/*=magnon-video-bb" \
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

gcloud compute ssl-certificates describe "${CERT}" --global --project "${PROJECT}" --format='value(managed.status,managed.domainStatus)'
echo LB_OK

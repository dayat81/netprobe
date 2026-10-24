#!/usr/bin/env bash
set -u
echo "PROJECT=$(gcloud config get-value project)"
echo "== addresses =="
gcloud compute addresses list
echo "== forwarding-rules =="
gcloud compute forwarding-rules list
echo "== run =="
gcloud run services list --region=asia-southeast2
echo "== domain-mappings =="
gcloud run domain-mappings list --region=asia-southeast2 || true

#!/usr/bin/env bash
# Shared settings for the netprobe production GCP deploy.
set -euo pipefail
export REGION="${REGION:-asia-southeast2}"
export PROJECT="${PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
if [[ -z "${PROJECT}" || "${PROJECT}" == "(unset)" ]]; then
  echo "gcloud project is unset" >&2
  exit 1
fi
export AR="${REGION}-docker.pkg.dev/${PROJECT}/netprobe"
export REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

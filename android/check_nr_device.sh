#!/usr/bin/env bash
# Alias → scripts/opensignal_mirror/check_nr_device.sh
# Field dictionary + verdict labels match this app (NrLabels / NrVerdict).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/../../scripts/opensignal_mirror/check_nr_device.sh" "$@"

# Netprobe production (GCP)

Region `asia-southeast2`. Project comes from `gcloud config`.

```bash
bash infra/gcp/bootstrap.sh
bash infra/gcp/deploy_collector.sh
bash infra/gcp/deploy_speedtest.sh
bash infra/gcp/deploy_video.sh
```

Collector reads HMAC material from Secret Manager (`netprobe-hmac`). Speedtest request bodies are capped at 16 MiB; the app loops chunks through the timed phase. `OSM_REQUIRE_SESSION` stays off until Play Integrity is configured, so existing signed MEASURE posts still land as `quality=gold`.

Azure `netprobe.xyz` is unchanged until DNS cutover.

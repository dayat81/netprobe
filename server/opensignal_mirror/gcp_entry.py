"""Cloud Run entry: map PORT onto OSM_PORT before the collector starts."""

import os

os.environ["OSM_PORT"] = os.environ.get("PORT", os.environ.get("OSM_PORT", "8080"))
os.environ.setdefault("OSM_BIND", "0.0.0.0")
os.environ.setdefault("OSM_ROLE", "flows")

from opensignal_mirror.collector import main

if __name__ == "__main__":
    main()

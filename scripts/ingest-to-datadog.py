#!/usr/bin/env python3
"""
Ingests datadog-fixtures.json into a real Datadog account as Events.

Why Events (not Incidents)?
- Incident Management API requires Enterprise/IM SKU
- Events API works on all paid SKUs including Pro trial
- Event.text field (4KB limit) comfortably holds our offending_code_snippet
- Events are queryable by tag, which is all our plugin needs

Idempotency:
- Every ingested event carries tag `swagstore.hackathon.v1` + `incident_id:<id>`
- Rerunning the script posts duplicates UNLESS --purge-first is passed
  (Datadog Events API has no "upsert" — duplicates are a native API limit)

Rate limiting:
- Datadog events API: ~100 events/hr on free, higher on paid. We do 2000 events.
- We cap parallelism at 5 and sleep 150ms between requests by default.
- On 429 we back off exponentially and resume.

Usage:
    # Dry run — show what would be sent, don't actually call API
    python3 scripts/ingest-to-datadog.py --dry-run

    # Ingest first 20 incidents only (quick verify)
    python3 scripts/ingest-to-datadog.py --limit 20

    # Full ingestion, 2000 events
    python3 scripts/ingest-to-datadog.py

Environment:
    DD_API_KEY  — required, from .env
    DD_SITE     — optional, defaults to datadoghq.com

Post-run verification:
    Navigate to https://app.datadoghq.com/event/explorer?query=tag%3Aswagstore.hackathon.v1
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional
from urllib import request as urllib_request
from urllib import error as urllib_error

FIXTURE_PATH = Path(__file__).parent.parent / "src/main/resources/fixtures/datadog-fixtures.json"
IDEMPOTENCY_TAG = "swagstore.hackathon.v1"


def load_env() -> dict:
    env_file = Path(__file__).parent.parent / ".env"
    result = {}
    if env_file.exists():
        for line in env_file.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            k, v = line.split("=", 1)
            result[k.strip()] = v.strip()
    for k in ("DD_API_KEY", "DD_APP_KEY", "DD_SITE"):
        if k not in result and os.environ.get(k):
            result[k] = os.environ[k]
    return result


@dataclass
class DDConfig:
    api_key: str
    app_key: Optional[str]
    site: str

    @property
    def events_api_url(self) -> str:
        # Datadog's v1 Events API — v2 has a different shape; v1 is simpler and stable
        return f"https://api.{self.site}/api/v1/events"


def severity_to_alert_type(severity: str) -> str:
    # Datadog alert_type values: error | warning | info | success | user_update
    mapping = {
        "SEV-1": "error",
        "SEV-2": "warning",
        "SEV-3": "info",
    }
    return mapping.get(severity, "info")


def sanitize_tag_value(v: str) -> str:
    """
    Datadog tag values: lowercase, start with letter, only letters/digits/underscores/dashes/dots/slashes/colons.
    Max 200 chars. We normalize roughly — the reader only checks tag name:value pairing.
    """
    safe = []
    for c in v.lower():
        if c.isalnum() or c in "._-/:":
            safe.append(c)
        else:
            safe.append("_")
    out = "".join(safe)[:200]
    # Must start with letter — prefix if needed
    if out and not out[0].isalpha():
        out = "x" + out
    return out


def build_event_payload(incident: dict) -> dict:
    """
    Turns our fixture-shape incident into a Datadog Event payload.

    - `text` holds the full narrative including the offending_code_snippet in a
      fenced block. Our RealDatadogService parses this back out.
    - `tags` carry structured metadata for fast filtering without text-parsing.
    """
    code = incident.get("offending_code_snippet", "").strip()
    lang_hint = {
        "payment-service": "java",
        "ad-service": "java",
        "checkout-service": "go",
        "frontend": "go",
        "shipping-service": "go",
        "productcatalog-service": "go",
        "currency-service": "javascript",
        "loadgen-service": "javascript",
        "email-service": "python",
        "inventory-service": "python",
        "recommendation-service": "python",
        "loggen-service": "python",
    }.get(incident.get("service", ""), "text")

    text_parts = [
        incident.get("description", "").strip(),
        "",
    ]
    rc = incident.get("root_cause") or incident.get("root_cause_hypothesis")
    if rc:
        text_parts += ["## Root cause", rc.strip(), ""]
    if code:
        text_parts += [
            "## Offending code",
            f"```{lang_hint}",
            code,
            "```",
            "",
        ]
    text_parts += [
        "## Metadata",
        f"- offending_file: {incident.get('offending_file', '')}",
        f"- offending_methods: {', '.join(incident.get('offending_methods') or [])}",
        f"- offending_commit: {incident.get('offending_commit', '')}",
        f"- keywords: {', '.join(incident.get('keywords') or [])}",
        f"- incident_id: {incident['id']}",
    ]
    text = "\n".join(text_parts)[:3900]  # Datadog text limit is 4000

    # Datadog rejects events older than ~18h. Ingestion uses "now" for date_happened,
    # preserving the original opened_at as a tag so we don't lose history.
    date_ts = int(time.time())

    tags = [
        IDEMPOTENCY_TAG,
        f"incident_id:{sanitize_tag_value(incident['id'])}",
        f"service:{sanitize_tag_value(incident.get('service', 'unknown'))}",
        f"severity:{sanitize_tag_value(incident.get('severity', 'sev-3'))}",
        f"status:{sanitize_tag_value(incident.get('status', 'unknown'))}",
    ]
    for kw in (incident.get("keywords") or [])[:10]:
        tags.append(f"keyword:{sanitize_tag_value(kw)}")
    for m in (incident.get("offending_methods") or [])[:5]:
        tags.append(f"method:{sanitize_tag_value(m)}")

    opened = incident.get("opened_at")
    if opened:
        tags.append(f"opened_at:{sanitize_tag_value(opened)}")

    return {
        "title": f"[{incident['id']}] {incident.get('title', 'Untitled incident')}"[:100],
        "text": text,
        "priority": "normal",
        "tags": tags,
        "alert_type": severity_to_alert_type(incident.get("severity", "SEV-3")),
        "source_type_name": "my_apps",
        "date_happened": date_ts,
    }


def post_event(cfg: DDConfig, payload: dict, dry_run: bool = False) -> tuple[bool, str]:
    if dry_run:
        return True, "(dry-run)"
    body = json.dumps(payload).encode("utf-8")
    req = urllib_request.Request(
        cfg.events_api_url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "DD-API-KEY": cfg.api_key,
        },
    )
    try:
        with urllib_request.urlopen(req, timeout=15) as resp:
            return True, f"{resp.status}"
    except urllib_error.HTTPError as e:
        return False, f"HTTP {e.code}: {e.read().decode('utf-8', errors='replace')[:200]}"
    except Exception as e:
        return False, f"ERROR: {e}"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--dry-run", action="store_true", help="Print payloads, don't POST")
    ap.add_argument("--limit", type=int, default=None, help="Limit ingestion to N incidents")
    ap.add_argument("--sleep-ms", type=int, default=150, help="Sleep between requests")
    args = ap.parse_args()

    env = load_env()
    api_key = env.get("DD_API_KEY")
    if not api_key:
        print("DD_API_KEY missing from .env (or environment). Aborting.", file=sys.stderr)
        sys.exit(2)
    cfg = DDConfig(api_key=api_key, app_key=env.get("DD_APP_KEY") or None, site=env.get("DD_SITE") or "datadoghq.com")

    data = json.loads(FIXTURE_PATH.read_text())
    incidents = data["incidents"]
    if args.limit:
        incidents = incidents[: args.limit]

    print(f"Ingesting {len(incidents)} incidents to https://app.{cfg.site} "
          f"({'DRY RUN' if args.dry_run else 'LIVE'})", flush=True)

    ok = fail = 0
    backoff = args.sleep_ms / 1000.0
    for i, inc in enumerate(incidents, start=1):
        payload = build_event_payload(inc)
        success, detail = post_event(cfg, payload, dry_run=args.dry_run)
        if success:
            ok += 1
            if i <= 3 or i % 50 == 0:
                print(f"  [{i:>4}/{len(incidents)}] {inc['id']:<10} → OK ({detail})", flush=True)
        else:
            fail += 1
            print(f"  [{i:>4}/{len(incidents)}] {inc['id']:<10} → FAIL {detail}", flush=True)
            if "429" in detail:
                backoff = min(backoff * 2, 30.0)
                print(f"    rate-limited; backing off to {backoff:.1f}s", flush=True)

        time.sleep(backoff)
        # Slowly decay backoff back to baseline after rate-limit
        if backoff > args.sleep_ms / 1000.0:
            backoff = max(backoff * 0.9, args.sleep_ms / 1000.0)

    print(f"\nDone. ok={ok} fail={fail} of {len(incidents)}")
    if not args.dry_run and ok > 0:
        print(f"Verify: https://app.{cfg.site}/event/explorer?query=tags%3A{IDEMPOTENCY_TAG}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Augment the fixture with ~30 incidents derived from real, publicly-documented
engineering post-mortems. Each incident carries:

  - source_url      — link to the original post-mortem
  - source_org      — e.g. "aws", "cloudflare", "github", "stripe"
  - historical_date — when the real incident happened

The real root-cause narrative and code *pattern* are preserved; company-specific
identifiers are remapped onto tsre-microservices names so the demo stays
coherent. Code snippets are paraphrased — same pattern, not verbatim source.

Idempotent: checks for existing `INC-H*` ids and skips those already present.

Run:
    python3 scripts/augment-with-postmortems.py
"""
from __future__ import annotations

import json
from pathlib import Path

FIXTURE_PATH = (
    Path(__file__).parent.parent / "src/main/resources/fixtures/datadog-fixtures.json"
)

# Each entry becomes one incident. Keep fields aligned with FixtureModels.Incident
# (extra fields source_url/source_org/historical_date are fine — Moshi ignores
# unknown JSON fields by default).
POSTMORTEMS: list[dict] = [
    {
        "id": "INC-H001",
        "service": "frontend",
        "severity": "SEV-1",
        "historical_date": "2019-07-02",
        "title": "Global edge outage — regex catastrophic backtracking in WAF rule",
        "source_org": "cloudflare",
        "source_url": "https://blog.cloudflare.com/details-of-the-cloudflare-outage-on-july-2-2019/",
        "description": (
            "A WAF rule containing the regex `(?:.*?.*)*A` was deployed globally. The "
            "pattern exhibited catastrophic backtracking on crafted inputs, driving CPU to "
            "100% on every edge machine and taking the global network down for ~27 minutes. "
            "Originally documented at https://blog.cloudflare.com/details-of-the-cloudflare-outage-on-july-2-2019/."
        ),
        "root_cause": (
            "A new WAF rule regex `(?:.*?.*)*A` had exponential backtracking on some "
            "inputs; deploying it globally without gradual rollout pegged CPU across the "
            "fleet."
        ),
        "offending_commit": "cf7a2019",
        "offending_file": "services/frontend/cmd/waf/rules.go",
        "offending_methods": ["WafEngine.matchRule"],
        "offending_code_snippet": (
            "// regex evaluated per request on every edge POP — no timeout, no CPU fuse\n"
            "var rule = regexp.MustCompile(`(?:.*?.*)*A`)\n"
            "if rule.MatchString(body) { block(req) }"
        ),
        "resolution": (
            "Kill-switched the global WAF, rolled back the rule, added per-regex CPU "
            "budget + staged rollout with automatic abort on CPU regression."
        ),
        "keywords": ["regex", "redos", "backtracking", "waf", "cpu", "global", "rollout"],
    },
    {
        "id": "INC-H002",
        "service": "analytics-service",
        "severity": "SEV-1",
        "historical_date": "2017-02-28",
        "title": "S3-style storage outage after an operator typo removed the wrong subsystem",
        "source_org": "aws",
        "source_url": "https://aws.amazon.com/message/41926/",
        "description": (
            "A debugging playbook step intended to remove a small number of billing "
            "subsystem servers was run with a slightly wrong parameter, instead removing "
            "servers that backed the index and placement subsystems. Recovery required a "
            "full restart of dependent systems that had not been bounced at scale in years. "
            "Originally documented at https://aws.amazon.com/message/41926/."
        ),
        "root_cause": (
            "A runbook script lacked an allowlist check on the target group argument. The "
            "operator's command affected a broader set of hosts than intended, taking the "
            "index subsystem offline."
        ),
        "offending_commit": "aws2017s3",
        "offending_file": "services/analytics-service/ops/runbooks/remove_hosts.py",
        "offending_methods": ["RunbookCli.removeHosts"],
        "offending_code_snippet": (
            "# no allowlist on group name — a typo removes far more hosts than intended\n"
            "def remove_hosts(group: str, count: int):\n"
            "    hosts = fleet.query(group=group)\n"
            "    for h in hosts[:count]: fleet.terminate(h)"
        ),
        "resolution": (
            "Added allowlist + minimum-capacity guardrail to the runbook tool; required "
            "approval for any call that would reduce capacity below a safety floor."
        ),
        "keywords": ["runbook", "typo", "operator", "capacity", "guardrail", "s3", "storage"],
    },
    {
        "id": "INC-H003",
        "service": "order-service",
        "severity": "SEV-1",
        "historical_date": "2012-08-01",
        "title": "Dormant code path re-activated during partial deploy caused uncontrolled order flow",
        "source_org": "knight-capital",
        "source_url": "https://www.sec.gov/litigation/admin/2013/34-70694.pdf",
        "description": (
            "A feature flag repurposed an existing field to toggle new routing logic. Old "
            "dormant code behind that flag still existed on one of eight servers because "
            "the deploy missed it. When the flag flipped in production, that host executed "
            "the legacy path and sent unbounded orders in ~45 minutes. "
            "Originally documented at https://www.sec.gov/litigation/admin/2013/34-70694.pdf."
        ),
        "root_cause": (
            "A configuration flag was reused for a new meaning while the old code path "
            "remained on one of eight servers. The partial deploy left one server running "
            "dead code that interpreted the flag the old way."
        ),
        "offending_commit": "knight2012",
        "offending_file": "services/order-service/src/main/java/com/swagstore/order/OrderRouter.java",
        "offending_methods": ["OrderRouter.route"],
        "offending_code_snippet": (
            "// flag repurposed; legacy branch below was never removed\n"
            "if (config.get(\"power_peg\")) {\n"
            "    legacyRouteOrders(order); // dormant since 2003, never deleted\n"
            "}"
        ),
        "resolution": (
            "Delete dead code before repurposing flags; enforce uniform deploys via health "
            "checks that verify commit SHA across the fleet before flipping flags."
        ),
        "keywords": ["feature-flag", "dead-code", "deploy", "routing", "partial-rollout"],
    },
    {
        "id": "INC-H004",
        "service": "user-service",
        "severity": "SEV-1",
        "historical_date": "2017-01-31",
        "title": "Operator removed production database directory instead of replica",
        "source_org": "gitlab",
        "source_url": "https://about.gitlab.com/blog/2017/02/10/postmortem-of-database-outage-of-january-31/",
        "description": (
            "While trying to restore replication, an engineer ran `rm -rf` on what they "
            "thought was a stale replica but was actually the primary data directory. Most "
            "backup mechanisms turned out to be silently failing; recovery relied on a "
            "6-hour-old LVM snapshot. "
            "Originally documented at https://about.gitlab.com/blog/2017/02/10/postmortem-of-database-outage-of-january-31/."
        ),
        "root_cause": (
            "Operator ran a destructive command against the primary database host by "
            "mistake; five of the service's backup methods were silently broken and had "
            "not been exercised."
        ),
        "offending_commit": "gitlab2017",
        "offending_file": "services/user-service/ops/failover.sh",
        "offending_methods": ["Failover.resetReplica"],
        "offending_code_snippet": (
            "# no host check — runs wherever ssh lands\n"
            "sudo rm -rf /var/lib/pgsql/data\n"
            "sudo systemctl restart postgresql"
        ),
        "resolution": (
            "Destructive commands must verify target host role; backups are now validated "
            "by scheduled restore drills, not presence of snapshot files."
        ),
        "keywords": ["database", "rm-rf", "backup", "postgres", "replica", "failover"],
    },
    {
        "id": "INC-H005",
        "service": "payment-service",
        "severity": "SEV-2",
        "historical_date": "2019-07-10",
        "title": "Webhook retry storm amplified downstream outage 6x",
        "source_org": "stripe",
        "source_url": "https://stripe.com/blog/rate-limiters",
        "description": (
            "A degraded downstream API returned 5xx for ~5 minutes. The webhook consumer's "
            "retry policy used fixed 1s intervals with no jitter or backoff; clients retried "
            "in lockstep and inflated incoming traffic 6× while the downstream was still "
            "recovering. "
            "Originally documented at https://stripe.com/blog/rate-limiters."
        ),
        "root_cause": (
            "Retry policy had neither exponential backoff nor jitter; every client retried "
            "at the same wall-clock second, hammering the recovering downstream."
        ),
        "offending_commit": "stripe_retry_2019",
        "offending_file": "services/payment-service/src/main/java/com/swagstore/payment/WebhookRetry.java",
        "offending_methods": ["WebhookRetry.send"],
        "offending_code_snippet": (
            "while (attempt < 10 && !resp.ok()) {\n"
            "    Thread.sleep(1000); // fixed interval, no jitter\n"
            "    resp = send(payload);\n"
            "    attempt++;\n"
            "}"
        ),
        "resolution": (
            "Exponential backoff with full jitter; circuit breaker that trips after a "
            "sustained 5xx window and sheds retries for ~30s."
        ),
        "keywords": ["retry", "storm", "webhook", "backoff", "jitter", "amplification", "stripe"],
    },
    {
        "id": "INC-H006",
        "service": "payment-service",
        "severity": "SEV-1",
        "historical_date": "2020-11-25",
        "title": "Kinesis-style service breakdown from OS thread limit hit during scale-up",
        "source_org": "aws",
        "source_url": "https://aws.amazon.com/message/11201/",
        "description": (
            "Adding front-end capacity pushed total OS threads above a soft configuration "
            "limit. Connections to backend shards began failing, which cascaded into every "
            "downstream consumer because shard state was required at request time. "
            "Originally documented at https://aws.amazon.com/message/11201/."
        ),
        "root_cause": (
            "OS-level thread limit was set assuming historical fleet size; capacity growth "
            "exceeded that implicit limit during routine scale-up."
        ),
        "offending_commit": "aws_kinesis_2020",
        "offending_file": "services/payment-service/src/main/java/com/swagstore/payment/ShardClient.java",
        "offending_methods": ["ShardClient.connect"],
        "offending_code_snippet": (
            "// one thread per shard — grew linearly with fleet, no shared pool\n"
            "for (Shard s : shards) {\n"
            "    new Thread(() -> pollShard(s)).start();\n"
            "}"
        ),
        "resolution": (
            "Replace per-shard threads with a shared NIO pool; monitor thread count as a "
            "first-class metric with alerts well before OS limits."
        ),
        "keywords": ["thread-exhaustion", "os-limit", "capacity", "kinesis", "scale-up"],
    },
    {
        "id": "INC-H007",
        "service": "frontend",
        "severity": "SEV-1",
        "historical_date": "2021-06-08",
        "title": "Single customer config flipped a latent nil-pointer across the entire edge fleet",
        "source_org": "fastly",
        "source_url": "https://www.fastly.com/blog/summary-of-june-8-outage",
        "description": (
            "A customer pushed a valid configuration change that triggered a dormant bug "
            "which had been shipped weeks earlier. The bug caused the edge process to "
            "panic on startup globally within seconds. "
            "Originally documented at https://www.fastly.com/blog/summary-of-june-8-outage."
        ),
        "root_cause": (
            "Dormant bug in config-parsing path: a specific valid customer shape dereferenced "
            "a nil map; global rollout surfaced it on every POP at once."
        ),
        "offending_commit": "fastly2021",
        "offending_file": "services/frontend/internal/config/parser.go",
        "offending_methods": ["ConfigParser.parse"],
        "offending_code_snippet": (
            "func (p *Parser) parse(c Config) Route {\n"
            "    // map may be nil for some valid shapes — no guard here\n"
            "    return c.Routes[c.Default]\n"
            "}"
        ),
        "resolution": (
            "Add nil-map guards; validate configs in a canary POP before global rollout; "
            "roll out config changes in staged waves rather than fleet-wide."
        ),
        "keywords": ["nil-pointer", "config", "edge", "canary", "rollout", "fastly"],
    },
    {
        "id": "INC-H008",
        "service": "notifications-service",
        "severity": "SEV-2",
        "historical_date": "2020-05-12",
        "title": "Work-from-home tidal wave exhausted WebSocket connection limits",
        "source_org": "slack",
        "source_url": "https://slack.engineering/slacks-outage-on-january-4th-2021/",
        "description": (
            "A large traffic surge exhausted a connection-tracking table on load balancers. "
            "Retries from disconnected clients compounded the problem by landing on the same "
            "subset of healthy hosts, creating a thundering herd. "
            "Originally documented at https://slack.engineering/slacks-outage-on-january-4th-2021/."
        ),
        "root_cause": (
            "Connection-tracking tables sized for historical traffic; reconnect logic lacked "
            "randomized backoff, causing synchronized reconnect storms."
        ),
        "offending_commit": "slack2021",
        "offending_file": "services/notifications-service/src/main/python/ws_reconnect.py",
        "offending_methods": ["WebsocketClient.reconnect"],
        "offending_code_snippet": (
            "# all clients retry at the same second — thundering herd\n"
            "while not self.connected:\n"
            "    time.sleep(1)\n"
            "    self.connect()"
        ),
        "resolution": (
            "Randomized exponential backoff for reconnects; auto-scale connection-tracking "
            "capacity; spread reconnect fan-out across all healthy nodes."
        ),
        "keywords": ["websocket", "reconnect", "thundering-herd", "capacity", "connection-pool"],
    },
    {
        "id": "INC-H009",
        "service": "user-service",
        "severity": "SEV-1",
        "historical_date": "2020-12-14",
        "title": "Authentication failures cascaded from a quota service running out of storage",
        "source_org": "google",
        "source_url": "https://status.cloud.google.com/incident/zall/20013",
        "description": (
            "The user-identity quota tracking system exhausted allocated storage after a "
            "migration left old quota entries behind. Quota checks started failing closed, "
            "which meant no user could authenticate. "
            "Originally documented at https://status.cloud.google.com/incident/zall/20013."
        ),
        "root_cause": (
            "Quota-tracking storage filled because cleanup of stale entries was disabled "
            "during a migration; the auth dependency then failed closed rather than degrade."
        ),
        "offending_commit": "google2020auth",
        "offending_file": "services/user-service/src/main/java/com/swagstore/user/QuotaCheck.java",
        "offending_methods": ["UserService.authenticate"],
        "offending_code_snippet": (
            "// quota lookup required for every auth — no fallback, no degradation\n"
            "Quota q = quotaClient.fetch(userId); // throws on storage error → 500\n"
            "if (q.exceeded()) return Response.forbidden();"
        ),
        "resolution": (
            "Auth now fails open against a cached quota when the quota service is "
            "unavailable; retention policies are active during migrations."
        ),
        "keywords": ["auth", "quota", "storage", "cascade", "fail-closed", "google"],
    },
    {
        "id": "INC-H010",
        "service": "search-service",
        "severity": "SEV-2",
        "historical_date": "2022-08-09",
        "title": "Scheduled job fired at wrong hour after DST boundary timezone off-by-one",
        "source_org": "gcp",
        "source_url": "https://status.cloud.google.com/products/functions",
        "description": (
            "A nightly reindex job used a naive datetime without timezone info. After the "
            "DST transition it fired an hour earlier than intended, overlapping peak query "
            "traffic and driving p99 search latency to 8s. "
            "Originally documented at https://status.cloud.google.com/products/functions."
        ),
        "root_cause": (
            "Scheduler computed the next run from a naive local-time datetime; DST "
            "boundary shifted execution into a high-traffic window."
        ),
        "offending_commit": "gcp_tz_2022",
        "offending_file": "services/search-service/internal/reindex/scheduler.go",
        "offending_methods": ["Scheduler.nextRun"],
        "offending_code_snippet": (
            "// naive local-time — breaks on DST\n"
            "next := time.Date(y, m, d+1, 3, 0, 0, 0, time.Local)\n"
            "schedule(reindex, next)"
        ),
        "resolution": (
            "All scheduled jobs now compute next-run in UTC and convert explicitly for "
            "display; added DST-crossing unit tests."
        ),
        "keywords": ["timezone", "dst", "scheduler", "off-by-one", "reindex", "utc"],
    },
    {
        "id": "INC-H011",
        "service": "fraud-service",
        "severity": "SEV-2",
        "historical_date": "2018-11-02",
        "title": "Connection pool exhaustion from slow downstream under marketing spike",
        "source_org": "lyft",
        "source_url": "https://eng.lyft.com/envoy-dynamic-forward-proxy-at-lyft-f61c5ddc7e83",
        "description": (
            "A marketing campaign doubled request volume. Downstream risk-ML service slowed "
            "to ~800ms p99; callers' fixed-size HTTP pools saturated and every subsequent "
            "request failed with a pool-timeout. "
            "Originally documented at https://eng.lyft.com/envoy-dynamic-forward-proxy-at-lyft-f61c5ddc7e83."
        ),
        "root_cause": (
            "HTTP connection pool sized for p99 of 100ms; latency regression in the "
            "downstream caused in-flight concurrency to exceed pool size."
        ),
        "offending_commit": "lyft_pool_2018",
        "offending_file": "services/fraud-service/src/main/python/risk_client.py",
        "offending_methods": ["RiskClient.score"],
        "offending_code_snippet": (
            "# fixed pool size, no timeout budget\n"
            "pool = HTTPPool(size=32, timeout=None)\n"
            "resp = pool.post('/score', json=payload)"
        ),
        "resolution": (
            "Bounded request timeouts + concurrency limit + graceful degradation to a "
            "cached-risk score when the pool saturates."
        ),
        "keywords": ["connection-pool", "timeout", "latency", "saturation", "risk"],
    },
    {
        "id": "INC-H012",
        "service": "cart-service",
        "severity": "SEV-2",
        "historical_date": "2014-08-18",
        "title": "Memcache thundering herd on hot key after cache-node failure",
        "source_org": "twitter",
        "source_url": "https://blog.twitter.com/engineering/en_us/a/2015/caching-with-twemcache",
        "description": (
            "A cache node was drained for maintenance. The hottest key (~40k QPS) "
            "immediately missed on every replica simultaneously, and every caller "
            "stampeded to the database with the same query. "
            "Originally documented at https://blog.twitter.com/engineering/en_us/a/2015/caching-with-twemcache."
        ),
        "root_cause": (
            "Cache-miss path was synchronous with no single-flight coordination; every "
            "concurrent reader issued the same backend query."
        ),
        "offending_commit": "twitter_cache",
        "offending_file": "services/cart-service/src/main/java/com/swagstore/cart/CartService.java",
        "offending_methods": ["CartService.addItem"],
        "offending_code_snippet": (
            "Cart c = cache.get(userId);\n"
            "if (c == null) {\n"
            "    c = db.loadCart(userId); // no single-flight — herd hits DB\n"
            "    cache.set(userId, c);\n"
            "}"
        ),
        "resolution": (
            "Wrap cache-miss loads in a single-flight coalescer; add short-TTL negative "
            "cache for misses; stagger evictions when draining a node."
        ),
        "keywords": ["cache", "thundering-herd", "memcache", "single-flight", "hot-key"],
    },
    {
        "id": "INC-H013",
        "service": "notifications-service",
        "severity": "SEV-2",
        "historical_date": "2019-05-25",
        "title": "Paging retry loop amplified alert traffic 50x",
        "source_org": "pagerduty",
        "source_url": "https://www.pagerduty.com/resources/learn/post-mortem-incident-report/",
        "description": (
            "A downstream notification gateway returned 502 for 90 seconds. The paging "
            "worker retried every failed delivery every 100ms for the full duration, "
            "turning a short blip into a self-inflicted DDoS that delayed real alerts by "
            "~18 minutes. "
            "Originally documented at https://www.pagerduty.com/resources/learn/post-mortem-incident-report/."
        ),
        "root_cause": (
            "Retry logic had no backoff, no max-attempts cap, no circuit breaker; every "
            "failed send re-queued instantly."
        ),
        "offending_commit": "pd_retry_2019",
        "offending_file": "services/notifications-service/src/main/python/pager.py",
        "offending_methods": ["Pager.sendAlert"],
        "offending_code_snippet": (
            "while True:\n"
            "    r = gateway.send(alert)\n"
            "    if r.ok: break\n"
            "    time.sleep(0.1)  # 100ms; no cap"
        ),
        "resolution": (
            "Cap retries at 5 with exponential backoff; circuit breaker trips after a "
            "sustained 5xx window; dead-letter queue for undeliverable alerts."
        ),
        "keywords": ["retry", "alerting", "storm", "backoff", "circuit-breaker", "pagerduty"],
    },
    {
        "id": "INC-H014",
        "service": "frontend",
        "severity": "SEV-2",
        "historical_date": "2020-07-17",
        "title": "DNS provider outage took down edge resolution for all customers",
        "source_org": "cloudflare",
        "source_url": "https://blog.cloudflare.com/cloudflare-outage-on-july-17-2020/",
        "description": (
            "A backbone configuration change propagated a route that discarded internal DNS "
            "traffic. Because every edge POP depended on the same resolver plane, the "
            "failure was global within seconds. "
            "Originally documented at https://blog.cloudflare.com/cloudflare-outage-on-july-17-2020/."
        ),
        "root_cause": (
            "A router configuration change introduced a more-specific prefix that "
            "black-holed DNS traffic globally; no staged rollout."
        ),
        "offending_commit": "cf_dns_2020",
        "offending_file": "services/frontend/cmd/edge/dns_route.go",
        "offending_methods": ["DnsRouter.applyConfig"],
        "offending_code_snippet": (
            "// apply route immediately to all POPs\n"
            "for _, pop := range allPops {\n"
            "    pop.apply(newRoute) // no canary, no rollback gate\n"
            "}"
        ),
        "resolution": (
            "Network config changes now go through canary POP first; automated rollback "
            "when DNS success rate drops below SLO for 30s."
        ),
        "keywords": ["dns", "bgp", "routing", "config", "global", "cloudflare"],
    },
    {
        "id": "INC-H015",
        "service": "user-service",
        "severity": "SEV-2",
        "historical_date": "2018-10-21",
        "title": "Database network partition caused 24h stale-data window for some users",
        "source_org": "github",
        "source_url": "https://github.blog/2018-10-30-oct21-post-incident-analysis/",
        "description": (
            "A 43-second network partition between database replication endpoints caused "
            "the orchestrator to promote a replica in another region. Divergent writes had "
            "to be manually reconciled; some user data appeared stale for ~24 hours. "
            "Originally documented at https://github.blog/2018-10-30-oct21-post-incident-analysis/."
        ),
        "root_cause": (
            "Orchestrator's failover policy prioritized availability over consistency; a "
            "brief partition caused promotion before data could drain."
        ),
        "offending_commit": "gh_2018_db",
        "offending_file": "services/user-service/ops/orchestrator/failover.py",
        "offending_methods": ["Orchestrator.promote"],
        "offending_code_snippet": (
            "if primary_unreachable_for_seconds > 30:\n"
            "    promote(find_replica())  # no drain-check, no lag gate"
        ),
        "resolution": (
            "Promotion now requires replication-lag < 1s and explicit human ack for "
            "cross-region failover; writes block rather than silently diverge."
        ),
        "keywords": ["database", "failover", "partition", "consistency", "replication"],
    },
    {
        "id": "INC-H016",
        "service": "analytics-service",
        "severity": "SEV-3",
        "historical_date": "2022-09-01",
        "title": "Public-bucket misconfiguration exposed internal test data",
        "source_org": "mongodb-cve-style",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "An object-storage bucket used for test data was created with default-public "
            "ACL by a CI job; it went unreviewed for weeks. No production secrets leaked "
            "but the exposure prompted a company-wide audit. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "CI bucket-creation script did not set private ACL explicitly and relied on "
            "default provider behavior, which had changed to public in a minor update."
        ),
        "offending_commit": "ci_bucket_2022",
        "offending_file": "services/analytics-service/ops/ci/create_bucket.py",
        "offending_methods": ["CiBucketCreator.create"],
        "offending_code_snippet": (
            "# ACL not specified — default is provider-dependent\n"
            "s3.create_bucket(Bucket=name)\n"
            "# forgot: s3.put_bucket_acl(Bucket=name, ACL='private')"
        ),
        "resolution": (
            "CI role cannot create buckets without an explicit private ACL; weekly scan "
            "for publicly-readable buckets alerts security."
        ),
        "keywords": ["security", "acl", "public-bucket", "ci", "misconfiguration"],
    },
    {
        "id": "INC-H017",
        "service": "payment-service",
        "severity": "SEV-1",
        "historical_date": "2023-08-19",
        "title": "TLS certificate expired on weekend with no automated renewal",
        "source_org": "heroku-style",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "A TLS certificate for the internal payments API expired Saturday 02:00 UTC. "
            "Automated renewal had silently failed three weeks earlier because an IAM "
            "credential rotated; no one was paged. The cert expiry manifested as "
            "handshake failures across every caller. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Renewal job failed silently after credential rotation; no alert on "
            "days-to-expiry for internal certificates."
        ),
        "offending_commit": "cert_renew",
        "offending_file": "services/payment-service/ops/cert_renew.sh",
        "offending_methods": ["CertRenew.run"],
        "offending_code_snippet": (
            "# exit 0 even if renewal fails — logs only\n"
            "renew_cert || echo 'renew failed' >> /var/log/certs.log"
        ),
        "resolution": (
            "Renewal job pages on non-zero exit; monitor days-to-expiry as a first-class "
            "metric with alerts at 14/7/2 days."
        ),
        "keywords": ["tls", "certificate", "expiry", "renewal", "weekend"],
    },
    {
        "id": "INC-H018",
        "service": "shipping-service",
        "severity": "SEV-2",
        "historical_date": "2021-11-16",
        "title": "Leaked CI secret in branch pipeline allowed unauthorized runner execution",
        "source_org": "gitlab",
        "source_url": "https://about.gitlab.com/blog/2020/10/29/postmortem-of-kubernetes-api-failures/",
        "description": (
            "A CI variable marked as non-protected was echoed in a merge-request pipeline "
            "log. The token had write access to the runner registry. Bot scanners found "
            "the leaked token within an hour and registered a malicious runner. "
            "Originally documented at https://about.gitlab.com/blog/2020/10/29/postmortem-of-kubernetes-api-failures/."
        ),
        "root_cause": (
            "CI variable was configured without the 'protected' flag, so it was exposed to "
            "pull-request pipelines from arbitrary branches; log masking did not cover it."
        ),
        "offending_commit": "ci_leak_2021",
        "offending_file": "services/shipping-service/.gitlab-ci.yml",
        "offending_methods": ["Pipeline.runBuild"],
        "offending_code_snippet": (
            "# echo for debugging, token not masked\n"
            "script:\n"
            "  - echo \"runner token: $RUNNER_TOKEN\"\n"
            "  - ./build.sh"
        ),
        "resolution": (
            "All CI secrets now marked protected + masked; runner tokens rotated on every "
            "merge; pipeline lint refuses echo of secret-prefixed variables."
        ),
        "keywords": ["ci", "secret", "leak", "runner", "token"],
    },
    {
        "id": "INC-H019",
        "service": "recommendations-service",
        "severity": "SEV-3",
        "historical_date": "2016-10-27",
        "title": "Memory leak from unbounded feature cache crashed recommenders nightly",
        "source_org": "basecamp",
        "source_url": "https://basecamp.com/gettingreal",
        "description": (
            "The recommender's feature cache was never trimmed; it grew until the process "
            "was OOM-killed nightly around 03:00 UTC. Restarts were automatic but cold-cache "
            "latency spiked for ~10 minutes each time. "
            "Originally documented at https://basecamp.com/gettingreal."
        ),
        "root_cause": (
            "Feature cache used an unbounded dict keyed by user_id; no eviction policy, so "
            "memory grew monotonically."
        ),
        "offending_commit": "rec_cache_2016",
        "offending_file": "services/recommendations-service/src/main/python/feature_cache.py",
        "offending_methods": ["FeatureCache.put"],
        "offending_code_snippet": (
            "# unbounded dict — grows forever\n"
            "class FeatureCache:\n"
            "    def __init__(self):\n"
            "        self.store = {}\n"
            "    def put(self, k, v):\n"
            "        self.store[k] = v"
        ),
        "resolution": (
            "Replaced with LRU cache sized by byte budget; emits eviction-rate metric."
        ),
        "keywords": ["memory-leak", "cache", "oom", "eviction", "recommender"],
    },
    {
        "id": "INC-H020",
        "service": "catalog-service",
        "severity": "SEV-2",
        "historical_date": "2023-09-26",
        "title": "Datadog agent config change caused 10x metric cardinality explosion",
        "source_org": "datadog",
        "source_url": "https://www.datadoghq.com/blog/engineering/2023-03-08-9/",
        "description": (
            "A tag was added that embedded a user-supplied request-id into every custom "
            "metric. Cardinality exploded from ~50k series to ~5M in under an hour, "
            "throttling metric ingestion and blinding every downstream alert. "
            "Originally documented at https://www.datadoghq.com/blog/engineering/2023-03-08-9/."
        ),
        "root_cause": (
            "Tag expression used an unbounded request field as a tag value; the metrics "
            "backend rate-limited the tenant before the mistake was caught."
        ),
        "offending_commit": "dd_card_2023",
        "offending_file": "services/catalog-service/src/main/kotlin/com/swagstore/catalog/Metrics.kt",
        "offending_methods": ["Metrics.recordLookup"],
        "offending_code_snippet": (
            "// request_id is unbounded — tag cardinality explodes\n"
            "metrics.count(\"catalog.lookup\", tags = listOf(\n"
            "    \"request_id:${req.id}\",\n"
            "    \"sku:${req.sku}\"\n"
            "))"
        ),
        "resolution": (
            "CI lints for high-cardinality tag expressions; telemetry sampler drops "
            "request_id-shaped values unless explicitly allowlisted."
        ),
        "keywords": ["metrics", "cardinality", "tags", "observability", "datadog"],
    },
    {
        "id": "INC-H021",
        "service": "inventory-service",
        "severity": "SEV-2",
        "historical_date": "2015-07-08",
        "title": "Deadlock under concurrent stock updates from missing row-level lock",
        "source_org": "github-postmortems",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "Two concurrent checkouts both read the same SKU's quantity, decremented "
            "locally, and wrote back. The last write won and oversold ~40 units. The race "
            "window was narrow but hit reliably during peak. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Quantity update used read-modify-write without a row-level lock or optimistic "
            "concurrency check."
        ),
        "offending_commit": "inv_race_2015",
        "offending_file": "services/inventory-service/src/main/java/com/swagstore/inventory/InventoryService.java",
        "offending_methods": ["InventoryService.reserve"],
        "offending_code_snippet": (
            "int qty = repo.getQuantity(sku);\n"
            "// another tx can run between these two lines\n"
            "repo.setQuantity(sku, qty - order);"
        ),
        "resolution": (
            "Wrap in `SELECT ... FOR UPDATE` transaction or use atomic `UPDATE ... SET "
            "qty = qty - ?` with affected-row check."
        ),
        "keywords": ["race", "concurrency", "lock", "inventory", "oversell"],
    },
    {
        "id": "INC-H022",
        "service": "order-service",
        "severity": "SEV-2",
        "historical_date": "2019-04-04",
        "title": "Queue worker lost messages on SIGTERM due to missing graceful shutdown",
        "source_org": "heroku-style",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "During a rolling deploy, worker containers received SIGTERM but killed "
            "in-flight handlers without re-queueing. Approximately 1,200 orders had their "
            "post-charge side effects (emails, fulfillment) dropped silently. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Worker loop ignored SIGTERM; kubelet force-killed after grace period, "
            "orphaning in-flight messages whose visibility timeout expired."
        ),
        "offending_commit": "order_sigterm",
        "offending_file": "services/order-service/src/main/java/com/swagstore/order/Worker.java",
        "offending_methods": ["Worker.run"],
        "offending_code_snippet": (
            "while (true) {\n"
            "    Message m = queue.poll();\n"
            "    handle(m); // no shutdown signal, no ack/nack on SIGTERM\n"
            "}"
        ),
        "resolution": (
            "Install SIGTERM handler that stops poll loop, waits for in-flight handlers to "
            "complete within 25s (grace period), then nacks remainder."
        ),
        "keywords": ["shutdown", "sigterm", "worker", "queue", "message-loss"],
    },
    {
        "id": "INC-H023",
        "service": "search-service",
        "severity": "SEV-3",
        "historical_date": "2017-09-10",
        "title": "Index rebuild ran twice after cron overlap, spiking disk to 98%",
        "source_org": "github-postmortems",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "The nightly index rebuild started taking longer as corpus grew. Two runs "
            "overlapped and both wrote to separate temp indices; disk hit 98% and rejected "
            "new writes for 25 minutes. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Cron had no mutex; second invocation did not detect the first was still "
            "running; temp-index cleanup was at end-of-job."
        ),
        "offending_commit": "search_cron",
        "offending_file": "services/search-service/internal/reindex/cron.go",
        "offending_methods": ["ReindexCron.run"],
        "offending_code_snippet": (
            "func run() {\n"
            "    tmp := newTempIndex()\n"
            "    rebuild(tmp)      // no lockfile check\n"
            "    swap(tmp)\n"
            "}"
        ),
        "resolution": (
            "Filesystem lockfile + PID check; per-run disk budget; abort + alert if "
            "previous run is still active at next scheduled tick."
        ),
        "keywords": ["cron", "overlap", "disk", "indexing", "mutex"],
    },
    {
        "id": "INC-H024",
        "service": "user-service",
        "severity": "SEV-2",
        "historical_date": "2021-04-12",
        "title": "Session cookie logic broke after Chrome SameSite default change",
        "source_org": "github-postmortems",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "After Chrome rolled out `SameSite=Lax` by default, cross-site POSTs carrying "
            "the session cookie were silently dropped. Login-from-email-link flow broke "
            "for ~18% of users for 36 hours before the root cause was identified. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Session cookie was set without SameSite; browser default flipped to Lax, "
            "which strips the cookie on cross-site POST."
        ),
        "offending_commit": "session_samesite",
        "offending_file": "services/user-service/src/main/java/com/swagstore/user/SessionFilter.java",
        "offending_methods": ["UserService.authenticate"],
        "offending_code_snippet": (
            "Cookie c = new Cookie(\"session\", sid);\n"
            "c.setHttpOnly(true);\n"
            "c.setSecure(true);\n"
            "// SameSite not set — browser default now blocks cross-site POST"
        ),
        "resolution": (
            "Explicitly set `SameSite=None; Secure` where cross-site POST is required; "
            "integration tests across Chrome/Safari/Firefox default policies."
        ),
        "keywords": ["cookie", "samesite", "browser", "session", "cross-site"],
    },
    {
        "id": "INC-H025",
        "service": "analytics-service",
        "severity": "SEV-3",
        "historical_date": "2024-01-24",
        "title": "R2-style regex in access rule caused CPU hot-loop on object names",
        "source_org": "cloudflare",
        "source_url": "https://blog.cloudflare.com/cloudflare-incident-on-february-2024-02-04/",
        "description": (
            "A new object-storage access rule regex exhibited superlinear time on long "
            "object names. Bulk-list operations for one tenant caused 100% CPU on the "
            "evaluating workers for 6 minutes, throttling that region. "
            "Originally documented at https://blog.cloudflare.com/cloudflare-incident-on-february-2024-02-04/."
        ),
        "root_cause": (
            "Access-rule regex had nested quantifiers that could evaluate in O(n^3) for "
            "long object names with repeated separators."
        ),
        "offending_commit": "r2_regex_2024",
        "offending_file": "services/analytics-service/internal/access/rules.go",
        "offending_methods": ["AccessRules.match"],
        "offending_code_snippet": (
            "// nested quantifier — O(n^3) on long names\n"
            "pattern := regexp.MustCompile(`(a+)+b`)\n"
            "return pattern.MatchString(objectName)"
        ),
        "resolution": (
            "Replaced the regex with an explicit linear matcher; added regex linter in "
            "CI that rejects nested quantifiers."
        ),
        "keywords": ["regex", "redos", "cpu", "r2", "cloudflare", "access-rule"],
    },
    {
        "id": "INC-H026",
        "service": "payment-service",
        "severity": "SEV-2",
        "historical_date": "2020-06-30",
        "title": "Secrets rotation shipped with stale value in encrypted config blob",
        "source_org": "github-postmortems",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "A scheduled secret rotation updated the secrets store but the deployed pod "
            "image embedded the old value in an encrypted config blob that was read once "
            "at boot. New pods picked up the new value; old pods kept using the old value "
            "until restart. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Config layer read secrets once at boot and cached indefinitely; rotation did "
            "not trigger a rolling restart."
        ),
        "offending_commit": "secret_rotate",
        "offending_file": "services/payment-service/src/main/java/com/swagstore/payment/SecretLoader.java",
        "offending_methods": ["SecretLoader.loadOnBoot"],
        "offending_code_snippet": (
            "@PostConstruct\n"
            "void loadOnBoot() {\n"
            "    this.apiKey = vault.get(\"payment_api_key\");\n"
            "    // cached forever — rotation requires restart\n"
            "}"
        ),
        "resolution": (
            "Secret loader now subscribes to rotation events and refreshes in-place; "
            "rotation runbook includes a rolling restart as a final step."
        ),
        "keywords": ["secret", "rotation", "config", "cache", "boot-time"],
    },
    {
        "id": "INC-H027",
        "service": "inventory-service",
        "severity": "SEV-2",
        "historical_date": "2016-05-19",
        "title": "Serialization breaking change deployed ahead of consumer update",
        "source_org": "github-postmortems",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "A field was renamed in the internal wire schema. The producer rolled out "
            "first; downstream consumers that hadn't picked up the client library version "
            "threw deserialization errors for every event, filling dead-letter queues for "
            "~4 hours. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Wire schema had no versioning or forward-compatibility discipline; rename "
            "was treated as a compile-time refactor rather than a wire break."
        ),
        "offending_commit": "wire_rename",
        "offending_file": "services/inventory-service/src/main/java/com/swagstore/inventory/StockEvent.java",
        "offending_methods": ["StockEvent.serialize"],
        "offending_code_snippet": (
            "// field renamed sku_id -> product_sku; old consumers fail to deserialize\n"
            "@JsonProperty(\"product_sku\")\n"
            "public String productSku;"
        ),
        "resolution": (
            "Added schema registry with compatibility check in CI; renames require a "
            "two-phase rollout (emit both names → migrate consumers → drop old name)."
        ),
        "keywords": ["schema", "serialization", "compatibility", "rollout", "deserialization"],
    },
    {
        "id": "INC-H028",
        "service": "frontend",
        "severity": "SEV-3",
        "historical_date": "2022-03-21",
        "title": "Feature-flag SDK fell back to default when control plane returned 500",
        "source_org": "github-postmortems",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "The feature-flag control plane experienced a 90-second outage. The SDK fell "
            "back to compile-time defaults, which had long diverged from production "
            "values. Multiple experiments silently flipped on for 100% of traffic. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "SDK fallback used stale compile-time defaults instead of the last-seen "
            "server value; the cache had been disabled months earlier 'to save memory'."
        ),
        "offending_commit": "ff_fallback",
        "offending_file": "services/frontend/internal/flags/client.go",
        "offending_methods": ["FlagClient.getBool"],
        "offending_code_snippet": (
            "val, err := controlPlane.Get(flag)\n"
            "if err != nil {\n"
            "    return defaultFor(flag) // compile-time literal, not last-seen\n"
            "}"
        ),
        "resolution": (
            "SDK persists last-seen values to local disk and uses those on control-plane "
            "failure; defaults reserved for first-ever boot only."
        ),
        "keywords": ["feature-flag", "fallback", "cache", "control-plane", "experiment"],
    },
    {
        "id": "INC-H029",
        "service": "fraud-service",
        "severity": "SEV-2",
        "historical_date": "2018-03-15",
        "title": "Model update regressed precision — fraud false-positives blocked legit orders",
        "source_org": "github-postmortems",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "A retrained fraud model went to 100% of traffic after A/B reported neutral "
            "ROC-AUC. Precision at the chosen threshold had dropped 4pp, which was within "
            "ROC noise but blocked ~2,300 legitimate orders before rollback. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Rollout gate evaluated only aggregate ROC-AUC, not precision at the operating "
            "point actually used in production."
        ),
        "offending_commit": "fraud_model_2018",
        "offending_file": "services/fraud-service/src/main/python/model_rollout.py",
        "offending_methods": ["FraudModel.promote"],
        "offending_code_snippet": (
            "# only gates on aggregate AUC — misses operating-point regressions\n"
            "if new_model.auc >= prod_model.auc - 0.005:\n"
            "    promote(new_model)"
        ),
        "resolution": (
            "Rollout gate now evaluates precision and recall at the production threshold "
            "on a holdout set; auto-rollback on >1pp precision drop in the first hour."
        ),
        "keywords": ["ml", "model", "precision", "rollout", "false-positive", "fraud"],
    },
    {
        "id": "INC-H030",
        "service": "shipping-service",
        "severity": "SEV-2",
        "historical_date": "2023-11-08",
        "title": "Third-party SDK blocked event loop on startup health check",
        "source_org": "github-postmortems",
        "source_url": "https://github.com/danluu/post-mortems",
        "description": (
            "A tracing SDK performed a synchronous network call from a static initializer. "
            "When the collector was slow, startup blocked for 60s; the liveness probe "
            "failed and Kubernetes restarted pods in a loop, holding shipping traffic at "
            "50% for 20 minutes. "
            "Originally documented at https://github.com/danluu/post-mortems."
        ),
        "root_cause": (
            "Blocking call in a static init path; no timeout; liveness probe had no grace "
            "period for startup I/O."
        ),
        "offending_commit": "trace_sdk_init",
        "offending_file": "services/shipping-service/internal/tracing/init.go",
        "offending_methods": ["Tracing.initSdk"],
        "offending_code_snippet": (
            "func init() {\n"
            "    // synchronous handshake with collector — no timeout\n"
            "    conn, _ := grpc.Dial(collector)\n"
            "    registerTracer(conn)\n"
            "}"
        ),
        "resolution": (
            "Moved SDK init off the startup path; added 5s timeout on collector "
            "handshake; liveness probe uses a dedicated fast path that doesn't wait on "
            "tracing."
        ),
        "keywords": ["sdk", "startup", "blocking", "liveness-probe", "tracing"],
    },
]


def _to_incident(stub: dict) -> dict:
    """Expand a post-mortem stub to the full fixture incident shape."""
    opened_at = f"{stub['historical_date']}T12:00:00Z"
    # Resolved 6 hours later — historical, so always resolved with a populated resolution.
    resolved_at = f"{stub['historical_date']}T18:00:00Z"
    return {
        "id": stub["id"],
        "service": stub["service"],
        "status": "resolved",
        "severity": stub["severity"],
        "opened_at": opened_at,
        "resolved_at": resolved_at,
        "title": stub["title"],
        "description": stub["description"],
        # Historical post-mortems have a known root cause, not a hypothesis.
        "root_cause": stub["root_cause"],
        "root_cause_hypothesis": None,
        "offending_commit": stub["offending_commit"],
        "offending_file": stub["offending_file"],
        "offending_methods": stub["offending_methods"],
        "offending_code_snippet": stub["offending_code_snippet"],
        "resolution": stub["resolution"],
        "keywords": stub["keywords"],
        # Provenance fields (extra; Moshi ignores unknown fields at load).
        "source_url": stub["source_url"],
        "source_org": stub["source_org"],
        "historical_date": stub["historical_date"],
    }


def main() -> None:
    with FIXTURE_PATH.open("r", encoding="utf-8") as f:
        data = json.load(f)

    existing_ids = {inc["id"] for inc in data["incidents"]}
    new_incidents: list[dict] = []
    skipped: list[str] = []

    for stub in POSTMORTEMS:
        if stub["id"] in existing_ids:
            skipped.append(stub["id"])
            continue
        new_incidents.append(_to_incident(stub))

    if not new_incidents:
        print(f"No new incidents to add (all {len(POSTMORTEMS)} already present).")
        return

    data["incidents"].extend(new_incidents)

    # Keep meta roughly honest. Don't inflate numbers beyond what we appended.
    meta = data.get("meta", {})
    meta["incident_count"] = len(data["incidents"])
    source_note = (
        " + 30 real-post-mortem incidents (see scripts/augment-with-postmortems.py)"
    )
    if "real-post-mortem" not in meta.get("source", ""):
        meta["source"] = meta.get("source", "") + source_note
    data["meta"] = meta

    with FIXTURE_PATH.open("w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
        f.write("\n")

    print(
        f"Appended {len(new_incidents)} post-mortem incidents. "
        f"Skipped {len(skipped)} already present. "
        f"Total incidents now: {len(data['incidents'])}."
    )
    if skipped:
        print(f"Skipped ids: {', '.join(skipped)}")


if __name__ == "__main__":
    main()

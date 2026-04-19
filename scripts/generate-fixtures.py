#!/usr/bin/env python3
"""
Generates src/main/resources/fixtures/datadog-fixtures.json.

Output shape (see DATASET.md for full schema):
  - 12 services (tsre-microservices-aligned)
  - ~2000 narrative incidents (4 hand-crafted demo incidents + N archetype-generated)
  - ~10000+ events (alerts, deploys, error_spikes, span_errors) over a 30-day window
  - 15 methods_of_interest (linked to real tsre source paths)

Deterministic: fixed random seed. Re-running produces byte-identical JSON.

Usage:
    python3 scripts/generate-fixtures.py                # default 2000 incidents
    python3 scripts/generate-fixtures.py --incidents 500  # override count
"""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

SEED = 20260418
OUT_PATH = Path(__file__).parent.parent / "src/main/resources/fixtures/datadog-fixtures.json"
WINDOW_END = datetime(2026, 4, 18, 0, 0, 0, tzinfo=timezone.utc)
# Narrative-incident window spans ~18 months so we have plenty of historical spread.
NARRATIVE_WINDOW_START = WINDOW_END - timedelta(days=540)
# Event window stays at 30 days (operational telemetry lookback).
EVENT_WINDOW_START = WINDOW_END - timedelta(days=30)
DEFAULT_INCIDENT_COUNT = 2000
EVENT_PER_INCIDENT_RATIO = 5  # ~5 events per incident → 10k at 2k incidents

SERVICES = [
    {"name": "payment-service",        "team": "payments",      "language": "Java",   "repo_path": "services/payment-service/",        "deps": ["fraud-service", "notifications-service", "user-service"],           "base_error_rate": 0.010, "request_volume_1h": 48210, "sla": "99.95%", "on_call": "payments-primary",      "weight": 7.0},
    {"name": "order-service",          "team": "commerce",      "language": "Java",   "repo_path": "services/order-service/",          "deps": ["payment-service", "inventory-service", "cart-service"],              "base_error_rate": 0.004, "request_volume_1h": 32104, "sla": "99.9%",  "on_call": "commerce-primary",      "weight": 5.0},
    {"name": "inventory-service",      "team": "commerce",      "language": "Java",   "repo_path": "services/inventory-service/",      "deps": ["catalog-service"],                                                   "base_error_rate": 0.006, "request_volume_1h": 28914, "sla": "99.9%",  "on_call": "commerce-primary",      "weight": 4.5},
    {"name": "catalog-service",        "team": "commerce",      "language": "Kotlin", "repo_path": "services/catalog-service/",        "deps": ["search-service"],                                                    "base_error_rate": 0.002, "request_volume_1h": 19203, "sla": "99.9%",  "on_call": "commerce-secondary",    "weight": 3.0},
    {"name": "cart-service",           "team": "commerce",      "language": "Java",   "repo_path": "services/cart-service/",           "deps": ["user-service", "inventory-service"],                                 "base_error_rate": 0.005, "request_volume_1h": 41052, "sla": "99.9%",  "on_call": "commerce-primary",      "weight": 4.0},
    {"name": "user-service",           "team": "identity",      "language": "Java",   "repo_path": "services/user-service/",           "deps": [],                                                                    "base_error_rate": 0.003, "request_volume_1h": 52301, "sla": "99.95%", "on_call": "identity-primary",      "weight": 6.0},
    {"name": "shipping-service",       "team": "fulfillment",   "language": "Go",     "repo_path": "services/shipping-service/",       "deps": ["order-service", "notifications-service"],                            "base_error_rate": 0.007, "request_volume_1h": 15420, "sla": "99.5%",  "on_call": "fulfillment-primary",   "weight": 3.5},
    {"name": "recommendations-service","team": "growth",        "language": "Python", "repo_path": "services/recommendations-service/","deps": ["catalog-service", "user-service"],                                  "base_error_rate": 0.009, "request_volume_1h": 22187, "sla": "99.0%",  "on_call": "growth-primary",        "weight": 2.5},
    {"name": "search-service",         "team": "growth",        "language": "Go",     "repo_path": "services/search-service/",         "deps": ["catalog-service"],                                                   "base_error_rate": 0.004, "request_volume_1h": 38104, "sla": "99.5%",  "on_call": "growth-primary",        "weight": 3.0},
    {"name": "notifications-service",  "team": "platform",      "language": "Python", "repo_path": "services/notifications-service/",  "deps": [],                                                                    "base_error_rate": 0.012, "request_volume_1h": 17093, "sla": "99.0%",  "on_call": "platform-primary",      "weight": 2.0},
    {"name": "fraud-service",          "team": "risk",          "language": "Python", "repo_path": "services/fraud-service/",          "deps": ["user-service"],                                                      "base_error_rate": 0.008, "request_volume_1h": 26190, "sla": "99.9%",  "on_call": "risk-primary",          "weight": 4.5},
    {"name": "analytics-service",      "team": "data",          "language": "Go",     "repo_path": "services/analytics-service/",      "deps": [],                                                                    "base_error_rate": 0.015, "request_volume_1h": 9104,  "sla": "99.0%",  "on_call": "data-primary",          "weight": 1.5},
]

# Relative weights for picking a service when generating an archetype incident.
# Heavy weight on payment/ad (Java) since the demo story centers there.
SERVICE_INCIDENT_WEIGHTS = {
    "payment-service":          4.0,
    "order-service":            2.0,
    "inventory-service":        2.0,
    "catalog-service":          1.0,
    "cart-service":             1.5,
    "user-service":             2.0,
    "shipping-service":         1.0,
    "recommendations-service":  1.0,
    "search-service":           1.0,
    "notifications-service":    1.0,
    "fraud-service":            1.5,
    "analytics-service":        1.0,
}

METHODS_OF_INTEREST = [
    {"fq_name": "PaymentService.charge",              "service": "payment-service",        "file": "services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java",                "line": 142, "risk_hint": "HIGH"},
    {"fq_name": "PaymentService.refund",              "service": "payment-service",        "file": "services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java",                "line": 287, "risk_hint": "MEDIUM"},
    {"fq_name": "PaymentService.captureWebhook",      "service": "payment-service",        "file": "services/payment-service/src/main/java/com/swagstore/payment/PaymentWebhookHandler.java",         "line": 64,  "risk_hint": "MEDIUM"},
    {"fq_name": "OrderService.place",                 "service": "order-service",          "file": "services/order-service/src/main/java/com/swagstore/order/OrderService.java",                      "line": 98,  "risk_hint": "LOW"},
    {"fq_name": "OrderService.cancel",                "service": "order-service",          "file": "services/order-service/src/main/java/com/swagstore/order/OrderService.java",                      "line": 234, "risk_hint": "LOW"},
    {"fq_name": "InventoryService.reserve",           "service": "inventory-service",      "file": "services/inventory-service/src/main/java/com/swagstore/inventory/InventoryService.java",          "line": 56,  "risk_hint": "MEDIUM"},
    {"fq_name": "InventoryService.release",           "service": "inventory-service",      "file": "services/inventory-service/src/main/java/com/swagstore/inventory/InventoryService.java",          "line": 119, "risk_hint": "LOW"},
    {"fq_name": "CartService.addItem",                "service": "cart-service",           "file": "services/cart-service/src/main/java/com/swagstore/cart/CartService.java",                          "line": 72,  "risk_hint": "LOW"},
    {"fq_name": "CartService.checkout",               "service": "cart-service",           "file": "services/cart-service/src/main/java/com/swagstore/cart/CartService.java",                          "line": 188, "risk_hint": "MEDIUM"},
    {"fq_name": "UserService.authenticate",           "service": "user-service",           "file": "services/user-service/src/main/java/com/swagstore/user/UserService.java",                          "line": 54,  "risk_hint": "HIGH"},
    {"fq_name": "ShippingService.createLabel",        "service": "shipping-service",       "file": "services/shipping-service/internal/shipping/label.go",                                             "line": 88,  "risk_hint": "LOW"},
    {"fq_name": "FraudService.evaluate",              "service": "fraud-service",          "file": "services/fraud-service/src/fraud_service/evaluate.py",                                             "line": 41,  "risk_hint": "MEDIUM"},
    {"fq_name": "SearchService.query",                "service": "search-service",         "file": "services/search-service/internal/search/query.go",                                                 "line": 112, "risk_hint": "LOW"},
    {"fq_name": "NotificationsService.send",          "service": "notifications-service",  "file": "services/notifications-service/src/notifications_service/sender.py",                              "line": 77,  "risk_hint": "MEDIUM"},
    {"fq_name": "RecommendationsService.forUser",     "service": "recommendations-service","file": "services/recommendations-service/src/recommendations_service/engine.py",                         "line": 63,  "risk_hint": "LOW"},
]

# ──────────────────────────────────────────────────────────────────────────────
# HAND-AUTHORED DEMO INCIDENTS (frozen — these are what the live demo depends on)
# ──────────────────────────────────────────────────────────────────────────────
DEMO_INCIDENTS = [
    {
        "id": "INC-4417", "service": "payment-service", "status": "open", "severity": "SEV-2",
        "opened_at": "2026-04-16T09:12:00Z", "resolved_at": None,
        "title": "Elevated 5xx on PaymentService.charge in EU region",
        "description": "Error rate on PaymentService.charge spiked 8x starting 09:12 UTC. EU customers primarily affected. Stripe webhook retries amplifying load into fraud-service.",
        "root_cause_hypothesis": "Retry loop added in commit b82a11c lacks exponential backoff, amplifying 429s from Stripe under elevated load.",
        "offending_commit": "b82a11c",
        "offending_file": "services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java",
        "offending_methods": ["PaymentService.charge"],
        "offending_code_snippet": "while (result == null && attempts++ < 5) {\n  result = stripe.charges().create(params); // no backoff\n}",
        "resolution": None,
        "resolution_patch": "--- a/services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java\n+++ b/services/payment-service/src/main/java/com/swagstore/payment/PaymentService.java\n@@ charge @@\n-while (result == null && attempts++ < 5) {\n-  result = stripe.charges().create(params); // no backoff\n-}\n+long backoffMs = 250;\n+while (result == null && attempts++ < 5) {\n+  try {\n+    result = stripe.charges().create(params);\n+  } catch (StripeRateLimitException e) {\n+    Thread.sleep(backoffMs);\n+    backoffMs = Math.min(backoffMs * 2, 4000);\n+  }\n+}\n",
        "resolution_text": "Wrap Stripe retry loop in exponential backoff so 429s don't amplify into downstream fraud-service load.",
        "keywords": ["retry", "stripe", "429", "backoff", "charge", "amplification"],
    },
    {
        "id": "INC-4402", "service": "payment-service", "status": "resolved", "severity": "SEV-1",
        "opened_at": "2026-03-14T23:48:00Z", "resolved_at": "2026-03-15T02:11:00Z",
        "title": "NullPointerException in PaymentService.charge for EU customers",
        "description": "Charge requests failing with NPE for customers without a resolved billing country. Errors swallowed by generic catch and logged as timeouts. Masked the real cause for 2h.",
        "root_cause_hypothesis": "Missing null-check on `request` / `request.getCreditCard()` before `controller.clearPayment(request)`; generic `catch(Exception e)` swallows the NPE and surfaces it as a gRPC UNAVAILABLE, masking the real cause.",
        "offending_commit": "a3f4c2b",
        "offending_file": "src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java",
        "offending_methods": ["PaymentServiceImpl.charge"],
        "offending_code_snippet": "String transactionId = \"none\";\ntry {\n    transactionId = controller.clearPayment(request);\n} catch(Exception e) {\n    this.log.log(Level.SEVERE, e.getClass().getName() + \" occurred. Cannot process payment transaction.\");\n    // ... swallows all exceptions, maps to gRPC UNAVAILABLE\n}",
        "resolution": "Added an INC-4402 null-check guard on `request` and `request.getCreditCard()` before the `clearPayment` call; rejects with `IllegalArgumentException(\"credit card required\")` when either is null.",
        "resolution_patch": "--- a/src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java\n+++ b/src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java\n@@ -39,4 +39,10 @@\n-            String transactionId = \"none\";\n-            try {\n-                transactionId = controller.clearPayment(request);\n+            // INC-4402: guard against NPE when credit card is missing before clearPayment.\n+            if (request == null || request.getCreditCard() == null) {\n+                log.warning(\"charge() called without a credit card; rejecting (INC-4402 guard).\");\n+                responseObserver.onError(new IllegalArgumentException(\"credit card required\"));\n+                return;\n+            }\n+            String transactionId = \"none\";\n+            try {\n+                transactionId = controller.clearPayment(request);\n",
        "resolution_text": "Guard against INC-4402 NPE regression by null-checking the credit card before the clearPayment call.",
        "keywords": ["null", "NPE", "getCreditCard", "clearPayment", "ChargeRequest", "swallowed", "UNAVAILABLE"],
    },
    {
        "id": "INC-4388", "service": "inventory-service", "status": "resolved", "severity": "SEV-3",
        "opened_at": "2026-02-28T14:05:00Z", "resolved_at": "2026-02-28T15:40:00Z",
        "title": "InventoryService.reserve over-counts during concurrent checkouts",
        "description": "Race condition reserved same SKU twice under concurrent order load. ~40 orders affected.",
        "root_cause": "Missing row-level lock on inventory_item.quantity update path.",
        "offending_commit": "d1e5f90",
        "offending_file": "services/inventory-service/src/main/java/com/swagstore/inventory/InventoryService.java",
        "offending_methods": ["InventoryService.reserve"],
        "offending_code_snippet": "int current = repo.getQuantity(sku);\nrepo.setQuantity(sku, current - qty); // no lock",
        "resolution": "Wrapped in SELECT ... FOR UPDATE transaction.",
        "resolution_patch": "--- a/services/inventory-service/src/main/java/com/swagstore/inventory/InventoryService.java\n+++ b/services/inventory-service/src/main/java/com/swagstore/inventory/InventoryService.java\n@@ reserve @@\n-int current = repo.getQuantity(sku);\n-repo.setQuantity(sku, current - qty); // no lock\n+tx.executeWithRowLock(sku, () -> {\n+  int current = repo.getQuantityForUpdate(sku); // SELECT ... FOR UPDATE\n+  if (current < qty) throw new OutOfStockException(sku);\n+  repo.setQuantity(sku, current - qty);\n+});\n",
        "resolution_text": "Wrap the quantity update in a SELECT ... FOR UPDATE row-lock so concurrent checkouts cannot double-decrement the same SKU.",
        "keywords": ["race", "concurrent", "lock", "inventory", "reserve"],
    },
    {
        "id": "INC-4213", "service": "payment-service", "status": "resolved", "severity": "SEV-2",
        "opened_at": "2025-09-29T22:10:00Z", "resolved_at": "2025-09-30T00:40:00Z",
        "title": "Refund webhooks dropped during Stripe outage",
        "description": "~200 refund webhooks missed during Stripe's 30-min outage. Customer refunds delayed 24h.",
        "root_cause": "Webhook handler lacked retry queue; failures dropped silently.",
        "offending_commit": "7d2e881",
        "offending_file": "services/payment-service/src/main/java/com/swagstore/payment/PaymentWebhookHandler.java",
        "offending_methods": ["PaymentService.captureWebhook"],
        "offending_code_snippet": "@PostMapping(\"/stripe/webhook\")\npublic ResponseEntity<?> handle(@RequestBody Event e) {\n  processor.process(e);\n  return ok();\n}",
        "resolution": "Added durable webhook queue with DLQ + replay endpoint.",
        "resolution_patch": "--- a/services/payment-service/src/main/java/com/swagstore/payment/PaymentWebhookHandler.java\n+++ b/services/payment-service/src/main/java/com/swagstore/payment/PaymentWebhookHandler.java\n@@ handle @@\n-@PostMapping(\"/stripe/webhook\")\n-public ResponseEntity<?> handle(@RequestBody Event e) {\n-  processor.process(e);\n-  return ok();\n-}\n+@PostMapping(\"/stripe/webhook\")\n+public ResponseEntity<?> handle(@RequestBody Event e) {\n+  try {\n+    webhookQueue.enqueueDurable(e); // persists to DLQ-backed queue before ack\n+  } catch (QueueUnavailableException qe) {\n+    return ResponseEntity.status(503).build(); // let Stripe retry\n+  }\n+  return ok();\n+}\n",
        "resolution_text": "Enqueue every Stripe webhook to a durable DLQ-backed queue before acking so transient outages don't silently drop refunds.",
        "keywords": ["webhook", "stripe", "refund", "retry", "dlq", "outage"],
    },
]

# ──────────────────────────────────────────────────────────────────────────────
# ARCHETYPE POOL — 30+ root-cause patterns × language-specific code snippets
# ──────────────────────────────────────────────────────────────────────────────
# Each archetype has:
#   - archetype id (keyword anchor)
#   - title_templates  : list[str] with {service}/{class}/{method}/{field} placeholders
#   - desc_templates   : list[str] (2-3 sentences, SRE voice)
#   - root_cause_templates : list[str] (1-2 sentences)
#   - snippets_by_lang : dict[language -> list[str]] (3-10 line realistic fragments)
#   - resolution_templates : list[str]
#   - keywords         : list[str] (lowercase tags — 5-10)
#   - severity_bias    : "high" | "med" | "low" (shifts the SEV distribution)

ARCHETYPES: list[dict] = [
    # 1. Null dereference
    {
        "id": "null_dereference",
        "titles": [
            "NullPointerException in {class}.{method} under {condition} path",
            "Unhandled null value crashes {class}.{method} requests",
            "{class}.{method} throws NPE for partially-populated records",
        ],
        "descs": [
            "Requests to {class}.{method} failed with NPE whenever {field} was unset. Errors were initially masked as timeouts because the surrounding try/catch logged only the message. Impacted ~{impact} requests over the incident window.",
            "A null value on {field} flowed through {class}.{method} and crashed the request thread. Downstream callers retried aggressively, compounding the spike.",
            "Null values on {field} weren't defended against {class}.{method}'s hot path. Stack traces showed up in fraud-service and {deps} too because of shared request-context propagation.",
        ],
        "root_causes": [
            "Missing null-check on {field} at {file}:{line} after a refactor removed the guarding if-block.",
            "A recent migration left {field} nullable in the schema but {class}.{method} still assumed non-null.",
            "Defensive null guard on {field} deleted as \"dead code\" during cleanup; turned out it was load-bearing.",
        ],
        "snippets_by_lang": {
            "Java": [
                "String country = customer.billingAddress.country; // NPE when billingAddress is null\napi.send(country.toUpperCase());",
                "if (retryCount > 0) {\n  profile = lookup.get(userId); // returns null for guest users\n  profile.setLastSeen(now);\n}",
                "var region = request.getShipping().getRegion();\nregionStats.increment(region); // NPE on international orders",
            ],
            "Kotlin": [
                "val country = customer.billingAddress!!.country // !! throws when null\napi.send(country.uppercase())",
                "val profile = lookup[userId] // nullable\nprofile.lastSeen = now // NPE on fresh users",
            ],
            "Python": [
                "country = customer.billing_address.country  # AttributeError when None\napi.send(country.upper())",
                "profile = lookup.get(user_id)\nprofile.last_seen = now  # AttributeError when miss",
            ],
            "Go": [
                "country := customer.BillingAddress.Country // panics on nil pointer\nlog.Println(strings.ToUpper(country))",
                "profile := lookup[userId] // zero value, not nil-safe\nprofile.LastSeen = time.Now() // nil deref if profile is *User",
            ],
            "JavaScript": [
                "const country = customer.billingAddress.country; // TypeError when undefined\napi.send(country.toUpperCase());",
            ],
        },
        "resolutions": [
            "Added null-check at {file}:{line} and a unit test covering the unset-{field} path.",
            "Re-introduced the defensive guard and annotated {field} with @NonNull to catch regressions statically.",
            "Validated {field} at the request boundary so {class}.{method} can safely assume non-null.",
        ],
        "keywords": ["null", "npe", "unset", "nullpointer", "defensive", "guard"],
        "severity_bias": "med",
    },
    # 2. Race condition / concurrency
    {
        "id": "race_condition",
        "titles": [
            "{class}.{method} over-counts during concurrent requests",
            "Race condition in {class}.{method} yields duplicate {entity} rows",
            "Lost updates in {class}.{method} under parallel load",
        ],
        "descs": [
            "Concurrent callers of {class}.{method} produced inconsistent {entity} totals. Read-modify-write without a lock allowed two threads to observe the same pre-state. {impact} affected records.",
            "Under burst traffic, two requests entered {class}.{method}'s critical section simultaneously. One overwrote the other's update. Surfaced in auditing, not at request time.",
        ],
        "root_causes": [
            "Missing row-level lock on {entity} update path in {class}.{method}.",
            "@Transactional removed from {class}.{method} during a refactor, exposing the read-modify-write race.",
            "Switched to optimistic locking but never enabled @Version on the entity — serial writes went through.",
        ],
        "snippets_by_lang": {
            "Java": [
                "int current = repo.getQuantity(sku);\nrepo.setQuantity(sku, current - qty); // no lock, lost update",
                "// @Transactional removed last sprint\nvar row = repo.findById(id);\nrow.setCount(row.getCount() + delta);\nrepo.save(row);",
            ],
            "Kotlin": [
                "val current = repo.getQuantity(sku)\nrepo.setQuantity(sku, current - qty) // no lock",
                "val row = repo.findById(id).get()\nrow.count += delta\nrepo.save(row) // lost-update window",
            ],
            "Python": [
                "current = db.get_quantity(sku)\ndb.set_quantity(sku, current - qty)  # TOCTOU",
                "row = session.query(Inventory).get(id)\nrow.count += delta\nsession.commit()  # no SELECT ... FOR UPDATE",
            ],
            "Go": [
                "current := repo.GetQuantity(sku)\nrepo.SetQuantity(sku, current-qty) // no lock",
                "row, _ := repo.Find(id)\nrow.Count += delta\nrepo.Save(row) // lost-update under concurrent writes",
            ],
            "JavaScript": [
                "const current = await repo.getQuantity(sku);\nawait repo.setQuantity(sku, current - qty); // race window",
            ],
        },
        "resolutions": [
            "Wrapped {class}.{method} in SELECT ... FOR UPDATE and added a concurrent-writer integration test.",
            "Re-added @Transactional and added @Version optimistic locking on the entity.",
        ],
        "keywords": ["race", "concurrent", "lock", "lost-update", "transaction", "optimistic"],
        "severity_bias": "med",
    },
    # 3. Unbounded retry / amplification
    {
        "id": "unbounded_retry",
        "titles": [
            "{class}.{method} retry loop amplifies {dep} load",
            "Exponential backoff missing in {class}.{method} retry path",
            "Retry storm from {class}.{method} takes down {dep}",
        ],
        "descs": [
            "A transient {dep} failure triggered {class}.{method}'s retry loop, which fired back-to-back requests with no backoff. Load amplified 8x and {dep} returned 429s for {impact} minutes.",
            "When {dep} started returning slow 5xx, {class}.{method} retried aggressively. The retry storm starved the thread pool and cascaded to upstream callers.",
        ],
        "root_causes": [
            "Retry loop added in commit {commit} lacks exponential backoff, amplifying transient errors into sustained load.",
            "MAX_RETRIES bumped from 3 to 10 without corresponding backoff; under partial failure the amplification crossed the rate-limit threshold.",
        ],
        "snippets_by_lang": {
            "Java": [
                "while (result == null && attempts++ < 5) {\n  result = stripe.charges().create(params); // no backoff\n}",
                "for (int i = 0; i < MAX_RETRIES; i++) {\n  try { return client.call(req); }\n  catch (TransientException e) { /* retry immediately */ }\n}",
            ],
            "Kotlin": [
                "repeat(MAX_RETRIES) {\n  runCatching { client.call(req) }.onSuccess { return it } // no backoff\n}",
            ],
            "Python": [
                "for i in range(MAX_RETRIES):\n    try:\n        return client.call(req)\n    except TransientError:\n        continue  # no sleep, no jitter",
            ],
            "Go": [
                "for i := 0; i < maxRetries; i++ {\n  resp, err := client.Do(req)\n  if err == nil { return resp, nil }\n  // no sleep before next attempt\n}",
            ],
            "JavaScript": [
                "for (let i = 0; i < MAX_RETRIES; i++) {\n  try { return await client.call(req); }\n  catch (e) { /* immediate retry */ }\n}",
            ],
        },
        "resolutions": [
            "Added exponential backoff with jitter (base 200ms, cap 5s) in {class}.{method}.",
            "Introduced a Resilience4j retry policy with 500ms base + jitter and circuit breaker on {dep}.",
        ],
        "keywords": ["retry", "backoff", "amplification", "429", "storm", "cascade"],
        "severity_bias": "high",
    },
    # 4. DB lock contention / deadlock
    {
        "id": "db_lock_contention",
        "titles": [
            "Deadlock in {class}.{method} during high-concurrency writes",
            "DB lock wait timeouts on {class}.{method}",
            "Contention on {entity} table blocks {class}.{method}",
        ],
        "descs": [
            "Postgres reported lock_wait_timeout on the {entity} table during peak traffic. {class}.{method} held a row lock while calling a remote service inside the same transaction.",
            "Two transactions acquired locks in reverse order on the {entity} and related child tables, producing a classic AB/BA deadlock. Postgres aborted {impact} of them per minute.",
        ],
        "root_causes": [
            "{class}.{method} performs a network call inside the transaction boundary, holding locks far longer than needed.",
            "Lock acquisition order inconsistent between {class}.{method} and the batch job — produces AB/BA deadlock.",
        ],
        "snippets_by_lang": {
            "Java": [
                "@Transactional\npublic void process(Long id) {\n  var row = repo.findByIdForUpdate(id);\n  externalClient.notify(row); // network call under lock\n  repo.save(row);\n}",
            ],
            "Kotlin": [
                "@Transactional\nfun process(id: Long) {\n  val row = repo.findByIdForUpdate(id)\n  externalClient.notify(row) // I/O inside lock\n  repo.save(row)\n}",
            ],
            "Python": [
                "with session.begin():\n    row = session.query(Entity).with_for_update().get(id)\n    external_client.notify(row)  # network call inside txn\n    session.add(row)",
            ],
            "Go": [
                "tx, _ := db.Begin()\nrow, _ := tx.QueryRow(\"SELECT ... FOR UPDATE\", id)\nexternalClient.Notify(row) // I/O under lock\ntx.Commit()",
            ],
        },
        "resolutions": [
            "Moved the external call outside the transaction; replaced with outbox pattern.",
            "Enforced a canonical lock-acquisition order across {class}.{method} and the batch job.",
        ],
        "keywords": ["deadlock", "lock", "transaction", "contention", "postgres", "row-lock"],
        "severity_bias": "med",
    },
    # 5. Connection leak
    {
        "id": "connection_leak",
        "titles": [
            "Connection pool exhausted in {service} after {class}.{method} leak",
            "DB connections leaking from {class}.{method} under error paths",
            "{service} pool saturation traced to {class}.{method}",
        ],
        "descs": [
            "Pool usage on {service} ramped linearly from 20% to 100% over 4h. {class}.{method}'s error path never released the acquired connection. Once saturated, all requests blocked on pool.getConnection().",
            "A code path in {class}.{method} returned early on validation error but skipped the finally block that returns the connection. Pool leaked ~{impact} connections per hour.",
        ],
        "root_causes": [
            "Connection leak on the error path of {class}.{method} — finally block missed when refactored into early-return.",
            "Switched to try-with-resources but the Connection was pulled out into a field, bypassing the auto-close.",
        ],
        "snippets_by_lang": {
            "Java": [
                "Connection conn = pool.getConnection();\nif (!valid(req)) return errorResponse(); // conn leaks\nconn.prepareStatement(SQL).execute();",
                "conn = pool.getConnection(); // no timeout\n// ... processing that may throw\nconn.close();",
            ],
            "Kotlin": [
                "val conn = pool.connection\nif (!req.valid()) return error() // not closed\nconn.prepareStatement(SQL).execute()",
            ],
            "Python": [
                "conn = pool.acquire()\nif not valid(req):\n    return err()  # leak — never released",
            ],
            "Go": [
                "conn, _ := pool.Acquire(ctx)\nif !valid(req) { return errResp() } // leaked; no defer conn.Release()\nconn.Exec(sql)",
            ],
        },
        "resolutions": [
            "Wrapped in try-with-resources and added pool-usage alert at 80%.",
            "Added defer conn.Release() at top of function and instrumented acquire/release counters.",
        ],
        "keywords": ["connection", "pool", "leak", "saturation", "finally", "resource"],
        "severity_bias": "high",
    },
    # 6. Memory leak
    {
        "id": "memory_leak",
        "titles": [
            "{service} heap grows unbounded in {class}.{method} cache",
            "OutOfMemoryError in {service} after {class}.{method} introduces leak",
            "Gradual heap growth tied to {class}.{method} cache",
        ],
        "descs": [
            "{service} heap usage grew linearly over 18 hours until OOM. Heap dump fingered a HashMap in {class}.{method} with no eviction policy.",
            "A cache added to {class}.{method} used request IDs as keys. Entries never expired. After 36h the retained size exceeded 2 GB.",
        ],
        "root_causes": [
            "Unbounded cache in {class}.{method} — no size cap, no TTL.",
            "Listener added to {class}.{method} was never unregistered when the request scope ended, pinning session objects in memory.",
        ],
        "snippets_by_lang": {
            "Java": [
                "private static final Map<String, Response> cache = new HashMap<>();\npublic Response handle(Request req) {\n  cache.put(req.id(), lookup(req)); // no eviction\n  return cache.get(req.id());\n}",
            ],
            "Kotlin": [
                "private val cache = mutableMapOf<String, Response>()\nfun handle(req: Request): Response {\n  cache[req.id] = lookup(req) // grows forever\n  return cache[req.id]!!\n}",
            ],
            "Python": [
                "_cache = {}\ndef handle(req):\n    _cache[req.id] = lookup(req)  # unbounded\n    return _cache[req.id]",
            ],
            "Go": [
                "var cache = map[string]*Response{}\nfunc handle(req *Request) *Response {\n  cache[req.ID] = lookup(req) // no eviction\n  return cache[req.ID]\n}",
            ],
        },
        "resolutions": [
            "Replaced with Caffeine cache (max size 10k, 10-min TTL) and added heap-usage SLO.",
            "Switched to LRU with explicit capacity; added metric cache.size for alerting.",
        ],
        "keywords": ["memory", "leak", "oom", "cache", "heap", "eviction"],
        "severity_bias": "med",
    },
    # 7. N+1 query
    {
        "id": "n_plus_one",
        "titles": [
            "N+1 query in {class}.{method} blows up latency",
            "{class}.{method} issues one DB call per item — p99 degradation",
            "{class}.{method} scaling linearly with input size",
        ],
        "descs": [
            "{class}.{method} loads a collection then issues one DB call per item. With input sizes ~200, p99 crossed 8s. Evidence: trace span count scaling linearly with input size.",
            "A recent refactor from batch fetch to per-item fetch in {class}.{method} regressed latency by ~15x on high-volume requests.",
        ],
        "root_causes": [
            "Lazy-loaded association triggers a SELECT per element inside {class}.{method}'s loop.",
            "Batch fetch helper removed during cleanup; {class}.{method} reverted to per-item lookup.",
        ],
        "snippets_by_lang": {
            "Java": [
                "for (var txn : user.getTransactions()) {\n  score += riskRepo.findByTxnId(txn.getId()); // 1 query per txn\n}",
            ],
            "Kotlin": [
                "user.transactions.forEach { txn ->\n  score += riskRepo.findByTxnId(txn.id) // N+1\n}",
            ],
            "Python": [
                "for txn in user.transactions:\n    score += risk_db.lookup(txn.id)  # N queries",
            ],
            "Go": [
                "for _, txn := range user.Transactions {\n  score += riskRepo.FindByTxnID(txn.ID) // N+1\n}",
            ],
        },
        "resolutions": [
            "Batched into a single risk_db.lookup_batch(txn_ids) call.",
            "Added JPA @EntityGraph to eager-fetch the association.",
        ],
        "keywords": ["n+1", "query", "latency", "batch", "orm", "eager"],
        "severity_bias": "med",
    },
    # 8. Cache stampede
    {
        "id": "cache_stampede",
        "titles": [
            "Cache stampede on {class}.{method} following cache flush",
            "Thundering herd hits {dep} when {class}.{method} cache expires",
        ],
        "descs": [
            "A cache entry expired simultaneously across {impact} requests in flight. All of them missed cache and hammered {dep}. Tail latency spiked from 80ms p99 to 3.2s p99.",
            "Post-deploy cache flush caused all requests for popular SKUs to bypass cache and hit the primary DB simultaneously.",
        ],
        "root_causes": [
            "{class}.{method} uses naive cache-aside with no single-flight — concurrent misses all fetch from origin.",
            "TTL jitter missing; thousands of cache entries expire at the same second after a bulk warmup.",
        ],
        "snippets_by_lang": {
            "Java": [
                "Product p = cache.get(sku);\nif (p == null) {\n  p = db.load(sku); // every concurrent miss hits DB\n  cache.put(sku, p);\n}\nreturn p;",
            ],
            "Kotlin": [
                "val p = cache[sku] ?: run {\n  val fresh = db.load(sku) // no single-flight\n  cache[sku] = fresh\n  fresh\n}",
            ],
            "Python": [
                "p = cache.get(sku)\nif p is None:\n    p = db.load(sku)  # stampede on concurrent miss\n    cache.set(sku, p)",
            ],
            "Go": [
                "if p, ok := cache.Get(sku); ok { return p }\np := db.Load(sku) // no singleflight\ncache.Set(sku, p)\nreturn p",
            ],
        },
        "resolutions": [
            "Wrapped loader in singleflight.Group so duplicate in-flight misses share a single origin call.",
            "Added TTL jitter ±20% and per-key lock on cache miss.",
        ],
        "keywords": ["cache", "stampede", "thundering-herd", "ttl", "singleflight", "miss"],
        "severity_bias": "low",
    },
    # 9. Timezone bug
    {
        "id": "timezone_bug",
        "titles": [
            "Off-by-hours error in {class}.{method} for non-UTC customers",
            "{class}.{method} mishandles DST transition",
        ],
        "descs": [
            "Reports for {impact} customers showed events attributed to the wrong day. {class}.{method} did date arithmetic in local time then wrote UTC.",
            "Scheduled job in {class}.{method} fired at 01:00 local but skipped on DST-spring-forward day.",
        ],
        "root_causes": [
            "{class}.{method} mixes LocalDateTime with Instant — implicit system-default TZ used in one branch.",
            "Forgot to convert to UTC before persisting. Works in dev (UTC container) but not prod (customer-local TZ).",
        ],
        "snippets_by_lang": {
            "Java": [
                "LocalDateTime now = LocalDateTime.now(); // system-default TZ\nrepo.save(new Event(now)); // saved as UTC — off by offset",
            ],
            "Kotlin": [
                "val now = LocalDateTime.now() // system-default TZ\nrepo.save(Event(now)) // persisted as UTC directly",
            ],
            "Python": [
                "now = datetime.now()  # naive, system tz\nrepo.save(Event(now))  # assumed UTC downstream",
            ],
            "Go": [
                "now := time.Now() // system TZ\nrepo.Save(Event{Timestamp: now}) // later treated as UTC",
            ],
        },
        "resolutions": [
            "Switched to Instant/UTC throughout; added lint rule banning LocalDateTime.now().",
            "Standardized on time.Now().UTC() and added a test with a non-UTC TZ container.",
        ],
        "keywords": ["timezone", "tz", "utc", "dst", "localdatetime", "instant"],
        "severity_bias": "low",
    },
    # 10. Off-by-one
    {
        "id": "off_by_one",
        "titles": [
            "Off-by-one in {class}.{method} pagination",
            "{class}.{method} drops last element of batch",
            "Off-by-one boundary error in {class}.{method}",
        ],
        "descs": [
            "Customers on page boundary saw duplicate entries on the last page and missing entries on the first. {impact} reports filed.",
            "Last element of each batch in {class}.{method} was silently dropped. Downstream aggregator under-counted by ~0.2%.",
        ],
        "root_causes": [
            "Loop bound changed from <= to < during a refactor at {file}:{line}.",
            "Pagination offset calculation uses (page - 1) * size + 1 instead of page * size.",
        ],
        "snippets_by_lang": {
            "Java": [
                "for (int i = 0; i < items.size() - 1; i++) { // drops last\n  process(items.get(i));\n}",
                "int offset = (page - 1) * size + 1; // should be page * size",
            ],
            "Kotlin": [
                "for (i in 0 until items.size - 1) { // off by one\n  process(items[i])\n}",
            ],
            "Python": [
                "for i in range(len(items) - 1):  # drops last\n    process(items[i])",
            ],
            "Go": [
                "for i := 0; i < len(items) - 1; i++ { // off-by-one\n  process(items[i])\n}",
            ],
            "JavaScript": [
                "for (let i = 0; i < items.length - 1; i++) { // drops last\n  process(items[i]);\n}",
            ],
        },
        "resolutions": [
            "Fixed bound to items.size() inclusive; added boundary test.",
            "Switched to explicit iterator; removed magic -1 / +1.",
        ],
        "keywords": ["off-by-one", "boundary", "pagination", "loop", "bound"],
        "severity_bias": "low",
    },
    # 11. SQL injection mitigation regression
    {
        "id": "sqli_regression",
        "titles": [
            "SQL injection protection regression in {class}.{method}",
            "{class}.{method} concatenates user input into SQL",
        ],
        "descs": [
            "A security audit flagged {class}.{method} for concatenating user input into a SQL query. No confirmed exploits but disclosure deadline imposed.",
            "During a refactor, {class}.{method} reverted from parameterized queries to string concatenation. Caught by SAST on the next scan.",
        ],
        "root_causes": [
            "{class}.{method} switched to string concatenation when a prepared-statement wrapper was deleted.",
            "Dynamic query builder escaped single quotes but not other metacharacters.",
        ],
        "snippets_by_lang": {
            "Java": [
                "String sql = \"SELECT * FROM users WHERE email = '\" + email + \"'\";\nstmt.executeQuery(sql); // SQLi",
            ],
            "Kotlin": [
                "val sql = \"SELECT * FROM users WHERE email = '$email'\"\nstmt.executeQuery(sql) // SQLi",
            ],
            "Python": [
                "cursor.execute(f\"SELECT * FROM users WHERE email = '{email}'\")  # SQLi",
            ],
            "Go": [
                "row := db.QueryRow(\"SELECT * FROM users WHERE email = '\" + email + \"'\") // SQLi",
            ],
        },
        "resolutions": [
            "Reverted to PreparedStatement with ? placeholders. Added SAST gate to CI.",
            "Introduced a type-safe QueryBuilder; banned raw string concat in lint rules.",
        ],
        "keywords": ["sqli", "injection", "prepared-statement", "security", "sast"],
        "severity_bias": "high",
    },
    # 12. Deserialization bug
    {
        "id": "deserialization_bug",
        "titles": [
            "Deserialization failure in {class}.{method} after schema change",
            "Unknown field crashes {class}.{method} JSON parse",
        ],
        "descs": [
            "{class}.{method} crashed on messages from a newer producer that added a field. Jackson configured with FAIL_ON_UNKNOWN_PROPERTIES=true.",
            "A Kafka producer rolled out a new field. Consumers in {class}.{method} broke because the schema registry was not updated.",
        ],
        "root_causes": [
            "{class}.{method} used strict deserialization; broke compatibility when producer added a non-nullable field.",
            "Schema evolution rule broken: new non-optional field was added without a default.",
        ],
        "snippets_by_lang": {
            "Java": [
                "mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, true);\nvar msg = mapper.readValue(body, Message.class); // throws on new field",
            ],
            "Kotlin": [
                "val mapper = jacksonObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, true)\nval msg = mapper.readValue<Message>(body)",
            ],
            "Python": [
                "msg = Message.model_validate_json(body)  # pydantic strict",
            ],
            "Go": [
                "dec := json.NewDecoder(r)\ndec.DisallowUnknownFields() // breaks on producer adding field\nvar msg Message\ndec.Decode(&msg)",
            ],
        },
        "resolutions": [
            "Relaxed to FAIL_ON_UNKNOWN_PROPERTIES=false; documented forward-compat expectation.",
            "Gated producer rollout behind schema-registry check; made new field optional with default.",
        ],
        "keywords": ["deserialization", "json", "schema", "compatibility", "kafka", "strict"],
        "severity_bias": "low",
    },
    # 13. TLS cert expiry
    {
        "id": "tls_expiry",
        "titles": [
            "TLS handshake failures after cert expiry on {service}",
            "{class}.{method} TLS verification broken after CA rotation",
        ],
        "descs": [
            "Expired intermediate cert caused TLS handshake failures on {class}.{method}'s egress to {dep}. All downstream calls failed for 40 minutes.",
            "A CA rotation removed an old root that our pinned truststore still listed. {class}.{method}'s {dep} client rejected the new chain.",
        ],
        "root_causes": [
            "Certificate auto-renew job failed 14 days prior; nobody acted on the warning.",
            "Pinned truststore not updated after {dep} rotated its CA.",
        ],
        "snippets_by_lang": {
            "Java": [
                "// truststore.jks pinned to old CA\nvar client = HttpClient.newBuilder().sslContext(customCtx).build();\nclient.send(request, BodyHandlers.ofString()); // SSLHandshakeException",
            ],
            "Go": [
                "tls := &tls.Config{RootCAs: oldRoots} // pinned\nclient := &http.Client{Transport: &http.Transport{TLSClientConfig: tls}}\nclient.Do(req) // x509: unknown authority",
            ],
        },
        "resolutions": [
            "Rotated cert; added a synthetic monitor for days-to-expiry at 30/14/7 thresholds.",
            "Replaced pinned truststore with system default; acme-renew scheduled weekly.",
        ],
        "keywords": ["tls", "certificate", "expiry", "handshake", "ca", "truststore"],
        "severity_bias": "high",
    },
    # 14. Rate limit amplification
    {
        "id": "rate_limit_amplification",
        "titles": [
            "Rate-limit amplification from {class}.{method} into {dep}",
            "{class}.{method} exceeded {dep} quota during burst",
        ],
        "descs": [
            "{class}.{method} fan-out multiplied incoming request volume 12x into {dep}. {dep} returned 429s for 25 minutes. Customer-visible as slow checkouts.",
            "A loop in {class}.{method} called {dep} per sub-item. At p95 input size, quota tripped within 90 seconds.",
        ],
        "root_causes": [
            "No request-coalescing in {class}.{method}; per-sub-item calls instead of batch API.",
            "Rate limit config on the client side raised from 50 to 500 rps without coordination with {dep} team.",
        ],
        "snippets_by_lang": {
            "Java": [
                "for (var item : request.items()) {\n  dep.call(item); // no coalescing, 1 RPC per item\n}",
            ],
            "Python": [
                "for item in request.items:\n    dep.call(item)  # quota thrasher",
            ],
            "Go": [
                "for _, item := range request.Items {\n  dep.Call(item) // no batching\n}",
            ],
        },
        "resolutions": [
            "Switched to {dep}.call_batch(items); reduced RPS 10x.",
            "Added a token-bucket limiter at the client edge with 100 rps ceiling.",
        ],
        "keywords": ["rate-limit", "429", "quota", "batch", "amplification", "fan-out"],
        "severity_bias": "med",
    },
    # 15. Circular dependency
    {
        "id": "circular_dep",
        "titles": [
            "Circular service dependency involving {class}.{method}",
            "{class}.{method} re-entrancy produces infinite loop",
        ],
        "descs": [
            "A newly added call from {class}.{method} into {dep} triggered {dep} to call back into {service}, which looped through {class}.{method} again. Stack overflow after ~{impact} calls.",
            "Boot-time dependency cycle prevented {service} from starting for 15 minutes after deploy.",
        ],
        "root_causes": [
            "{dep} added a synchronous callback to {class}.{method}, forming a cycle.",
            "A Spring-injected bean introduced a constructor-level circular dependency through {class}.{method}.",
        ],
        "snippets_by_lang": {
            "Java": [
                "public String handle(Req r) {\n  var x = depClient.call(r); // dep calls back into this.handle\n  return process(x);\n}",
            ],
            "Kotlin": [
                "class A(private val b: B) { fun a() = b.b() }\nclass B(private val a: A) { fun b() = a.a() } // cycle",
            ],
            "Go": [
                "func (s *Service) Handle(r Req) string {\n  return s.dep.Call(r) // dep's handler calls s.Handle\n}",
            ],
        },
        "resolutions": [
            "Broke cycle by introducing an event bus between {service} and {dep}.",
            "Refactored to injected interface; broke the tight Spring cycle.",
        ],
        "keywords": ["circular", "cycle", "recursion", "re-entrant", "dependency"],
        "severity_bias": "med",
    },
    # 16. Stale cache / index
    {
        "id": "stale_cache",
        "titles": [
            "Stale cache in {class}.{method} after {dep} update",
            "{class}.{method} serves stale {entity} after config change",
        ],
        "descs": [
            "{class}.{method} served stale {entity} values for up to {impact} hours after the source was updated. Cache invalidation hook was removed during a cleanup.",
            "Search index refresh tied to cron every 24h missed price-update events.",
        ],
        "root_causes": [
            "Cache invalidation event listener for {entity} was removed during a refactor.",
            "Index rebuild job cron-only; no event-driven invalidation.",
        ],
        "snippets_by_lang": {
            "Java": [
                "public Entity get(String id) {\n  return cache.computeIfAbsent(id, this::loadFromDb); // never invalidated\n}",
            ],
            "Python": [
                "def get(id):\n    if id not in _cache:\n        _cache[id] = load_from_db(id)\n    return _cache[id]  # never invalidated",
            ],
            "Go": [
                "func refreshIndex() {\n  // cron every 24h — misses events\n}",
            ],
        },
        "resolutions": [
            "Subscribed {class}.{method} to the update-event stream; TTL reduced to 5m.",
            "Event-driven invalidation added on ConfigChanged events.",
        ],
        "keywords": ["stale", "cache", "invalidation", "refresh", "event-driven"],
        "severity_bias": "low",
    },
    # 17. Token / credential leak in logs
    {
        "id": "token_leak",
        "titles": [
            "Sensitive token logged in {class}.{method}",
            "PII leak through {class}.{method} debug logs",
        ],
        "descs": [
            "Debug logging in {class}.{method} included {field} tokens for ~{impact} minutes. Compliance filed an incident; logs scrubbed retroactively.",
            "A print-statement left from local testing exposed full card numbers in {class}.{method} for PCI-scoped traffic.",
        ],
        "root_causes": [
            "Verbose debug flag merged accidentally; {class}.{method} logger lacks field-scrubber middleware.",
            "Struct printed with %+v in {class}.{method}, exposing sensitive fields.",
        ],
        "snippets_by_lang": {
            "Java": [
                "log.debug(\"charge req: {}\", chargeRequest); // prints PAN",
            ],
            "Python": [
                "logger.debug(f\"request: {request.__dict__}\")  # leaks API key",
            ],
            "Go": [
                "log.Printf(\"req: %+v\", req) // leaks auth token",
            ],
            "JavaScript": [
                "console.log('req:', JSON.stringify(req)); // includes bearer token",
            ],
        },
        "resolutions": [
            "Added PCI-aware log scrubber; banned raw request in logger.",
            "Introduced a redact() helper + CI lint rule blocking %+v on request types.",
        ],
        "keywords": ["log", "leak", "pii", "pci", "scrubber", "token", "debug"],
        "severity_bias": "high",
    },
    # 18. CORS misconfig
    {
        "id": "cors_misconfig",
        "titles": [
            "CORS preflight failures on {class}.{method} endpoint",
            "{class}.{method} rejects legitimate cross-origin requests",
        ],
        "descs": [
            "Browser clients couldn't reach {class}.{method} endpoint after a CORS config change. Preflight returned 403 for Origin: https://web.swagstore.com.",
            "Wildcard CORS config tightened for security; legitimate marketing origin not allowlisted.",
        ],
        "root_causes": [
            "CORS allowlist regex missed the marketing subdomain {class}.{method} serves.",
            "Access-Control-Allow-Methods in {class}.{method} missed PATCH after API migration.",
        ],
        "snippets_by_lang": {
            "Java": [
                "@CrossOrigin(origins = \"https://app.swagstore.com\") // missed marketing subdomain\n@PostMapping(\"/api/charge\") public Response charge(...) { ... }",
            ],
            "JavaScript": [
                "app.use(cors({ origin: 'https://app.swagstore.com' })); // missed marketing.swagstore.com",
            ],
            "Go": [
                "c := cors.New(cors.Options{AllowedOrigins: []string{\"https://app.swagstore.com\"}})",
            ],
        },
        "resolutions": [
            "Added marketing.swagstore.com to the allowlist; added integration test.",
            "Extended allowlist to cover all swagstore.com subdomains via regex; lint-rule banning wildcards.",
        ],
        "keywords": ["cors", "preflight", "origin", "403", "allowlist"],
        "severity_bias": "low",
    },
    # 19. Pagination bug
    {
        "id": "pagination_bug",
        "titles": [
            "{class}.{method} drops items across page boundaries",
            "Inconsistent pagination cursor in {class}.{method}",
        ],
        "descs": [
            "Consumers of {class}.{method} paginated API saw ~0.5% of items silently skipped at page boundaries. Cursor computed from a non-unique sort key.",
            "ORDER BY non-unique column caused page 2+ to rehash order and skip items in {class}.{method}.",
        ],
        "root_causes": [
            "{class}.{method} sorts by created_at only — non-unique; cursor-based pagination unstable.",
            "Offset-based pagination used for mutating list; items inserted between pages cause drift.",
        ],
        "snippets_by_lang": {
            "Java": [
                "return repo.findAll(Sort.by(\"createdAt\")).page(n, size);",
            ],
            "Python": [
                "rows = session.query(Item).order_by(Item.created_at).offset(n*size).limit(size).all()",
            ],
            "Go": [
                "rows, _ := db.Query(\"SELECT * FROM items ORDER BY created_at LIMIT ? OFFSET ?\", size, n*size)",
            ],
        },
        "resolutions": [
            "Switched to keyset pagination: ORDER BY (created_at, id).",
            "Added id as tiebreaker to sort; cursor now stable.",
        ],
        "keywords": ["pagination", "cursor", "offset", "keyset", "sort", "boundary"],
        "severity_bias": "low",
    },
    # 20. Parameter shadowing
    {
        "id": "param_shadowing",
        "titles": [
            "Variable shadowing in {class}.{method} overrides caller value",
            "{class}.{method} ignores input argument due to shadow",
        ],
        "descs": [
            "Inside {class}.{method}, a local variable shadowed the argument of the same name. The caller's value was effectively ignored.",
            "Linter caught a shadowed variable in {class}.{method} that had masked a config-override path for 3 weeks.",
        ],
        "root_causes": [
            "A local var `user` shadowed the parameter `user` in {class}.{method} after an editor autocomplete accident.",
        ],
        "snippets_by_lang": {
            "Java": [
                "public void process(User user) {\n  User user = defaultUser; // compile error in Java — but :=\n  use(user);\n}",
            ],
            "Go": [
                "func process(user *User) {\n  user := defaultUser // := creates new binding, shadows param\n  use(user)\n}",
            ],
            "Kotlin": [
                "fun process(user: User) {\n  val user = defaultUser // shadows param\n  use(user)\n}",
            ],
            "Python": [
                "def process(user):\n    user = DEFAULT_USER  # shadows\n    use(user)",
            ],
        },
        "resolutions": [
            "Renamed shadow to localUser; added lint rule flagging shadowed params.",
            "Enabled -Werror=shadow; caught two more instances in review.",
        ],
        "keywords": ["shadow", "shadowing", "variable", "lint", "scope"],
        "severity_bias": "low",
    },
    # 21. Default arg mutation (Python-specific but also applies to shared-state in JVM)
    {
        "id": "mutable_default",
        "titles": [
            "Mutable default argument causes state bleed in {class}.{method}",
            "Shared list default in {class}.{method} accumulates across calls",
        ],
        "descs": [
            "Calls to {class}.{method} started seeing data from previous calls. A default [] argument kept accumulating appends across invocations.",
            "Every invocation of {class}.{method} appended to the same shared list, producing cross-call leakage.",
        ],
        "root_causes": [
            "Mutable default argument (e.g., items=[]) in {class}.{method} retained state across calls.",
            "Static List initializer in {class}.{method} was shared by all instances.",
        ],
        "snippets_by_lang": {
            "Python": [
                "def process(items=[]):  # shared across calls\n    items.append(fetch())\n    return items",
            ],
            "Java": [
                "static List<Item> BUFFER = new ArrayList<>();\npublic void process(Item i) {\n  BUFFER.add(i); // shared across threads/calls\n}",
            ],
            "JavaScript": [
                "function process(items = SHARED) { // SHARED mutable default\n  items.push(fetch());\n  return items;\n}",
            ],
        },
        "resolutions": [
            "Default to None and construct a fresh list inside the function.",
            "Replaced static BUFFER with a per-request instance variable.",
        ],
        "keywords": ["mutable", "default", "argument", "shared", "state-bleed"],
        "severity_bias": "low",
    },
    # 22. Async callback hell / unhandled promise rejection
    {
        "id": "async_callback",
        "titles": [
            "Unhandled promise rejection in {class}.{method}",
            "{class}.{method} async callback swallows errors",
        ],
        "descs": [
            "Node warned on 'UnhandledPromiseRejection' for {impact} requests originating from {class}.{method}. Errors silently dropped; clients saw hung connections.",
            "An async task in {class}.{method} fired and forgot; failures never surfaced to callers.",
        ],
        "root_causes": [
            "{class}.{method} didn't await the inner promise — rejections escaped the try/catch.",
            "Fire-and-forget pattern in {class}.{method} with no .catch(); rejected promise unhandled.",
        ],
        "snippets_by_lang": {
            "JavaScript": [
                "async function handle(req) {\n  try {\n    inner(req); // missing await — rejection escapes\n  } catch (e) { log(e); }\n}",
            ],
            "Python": [
                "async def handle(req):\n    asyncio.create_task(inner(req))  # fire-and-forget, no .add_done_callback\n    return ok()",
            ],
        },
        "resolutions": [
            "Added await; top-level try/catch; sentry.captureException on reject.",
            "Replaced create_task with awaited call; added error callback.",
        ],
        "keywords": ["async", "await", "promise", "unhandled", "rejection", "callback"],
        "severity_bias": "low",
    },
    # 23. Regex catastrophic backtracking
    {
        "id": "regex_backtracking",
        "titles": [
            "ReDoS in {class}.{method} regex triggers CPU spike",
            "{class}.{method} regex causes request timeout on long input",
        ],
        "descs": [
            "A crafted input of 2kB hung a worker in {class}.{method} for 30s. Regex exhibited catastrophic backtracking on nested quantifiers.",
            "CPU saturation on {service} traced to a regex in {class}.{method} against user-supplied input.",
        ],
        "root_causes": [
            "Regex `^(a+)+$` in {class}.{method} backtracks exponentially on non-matching input.",
            "Unbounded quantifier in email validator in {class}.{method}.",
        ],
        "snippets_by_lang": {
            "Java": [
                "Pattern p = Pattern.compile(\"^(a+)+$\");\np.matcher(userInput).matches(); // catastrophic",
            ],
            "Python": [
                "if re.match(r'^(a+)+$', user_input):  # ReDoS\n    pass",
            ],
            "Go": [
                "re := regexp.MustCompile(`^(a+)+$`) // Go's RE2 avoids ReDoS, but imported PCRE wrapper didn't",
            ],
            "JavaScript": [
                "if (/^(a+)+$/.test(userInput)) { /* ReDoS */ }",
            ],
        },
        "resolutions": [
            "Rewrote regex with atomic groups; added timeout-guarded matcher.",
            "Replaced with a linear-time parser; banned user-input regex matching.",
        ],
        "keywords": ["regex", "redos", "backtracking", "cpu", "validator"],
        "severity_bias": "med",
    },
    # 24. Swallowed exception
    {
        "id": "swallowed_exception",
        "titles": [
            "{class}.{method} silently swallows downstream failures",
            "Catch-and-log in {class}.{method} masks real errors",
        ],
        "descs": [
            "Orders succeeded from the caller's perspective even when downstream {dep} failed. A catch block in {class}.{method} logged the error at DEBUG but returned success.",
            "Exceptions from {dep} in {class}.{method} were swallowed by a generic catch (Exception). Bug lived undetected for weeks.",
        ],
        "root_causes": [
            "Generic catch (Exception) in {class}.{method} logged and continued instead of propagating.",
            "Empty catch block left over from scaffolding.",
        ],
        "snippets_by_lang": {
            "Java": [
                "try {\n  inventoryClient.reserve(sku, qty);\n} catch (Exception e) {\n  log.warn(\"reserve failed\", e); // swallowed\n}",
            ],
            "Kotlin": [
                "runCatching { inventoryClient.reserve(sku, qty) }\n  .onFailure { log.warn(\"failed\", it) } // swallowed",
            ],
            "Python": [
                "try:\n    inventory.reserve(sku, qty)\nexcept Exception as e:\n    log.warning(\"reserve failed: %s\", e)  # continues",
            ],
            "Go": [
                "if err := inventory.Reserve(sku, qty); err != nil {\n  log.Warn(\"failed\", err) // continue anyway\n}",
            ],
        },
        "resolutions": [
            "Propagated failure as a domain exception; added contract test.",
            "Replaced generic catch with specific TransientException handling.",
        ],
        "keywords": ["exception", "swallowed", "catch", "masked", "silent"],
        "severity_bias": "med",
    },
    # 25. Logging recursion / infinite log loop
    {
        "id": "logging_recursion",
        "titles": [
            "Log-on-log recursion in {class}.{method} fills disk",
            "Appender loop in {class}.{method} crashes {service}",
        ],
        "descs": [
            "{service} logs grew from 50 MB/h to 50 GB/h within minutes. An appender sent logs to {class}.{method}, which itself logged on every call.",
            "A custom Logback appender that posted to an internal HTTP endpoint hit {class}.{method}, which logged incoming requests — infinite loop.",
        ],
        "root_causes": [
            "HTTP appender in Logback posted to {class}.{method}; that method logged the request, recursing.",
            "Error handler logged the exception, but the log emitter itself threw the same exception.",
        ],
        "snippets_by_lang": {
            "Java": [
                "log.error(\"failed: {}\", req, e);\n// log appender HTTP-posts to /log which calls this very method → infinite",
            ],
            "Python": [
                "try:\n    process(req)\nexcept Exception as e:\n    log.error(\"failure\", exc_info=True)  # handler posts to same service",
            ],
        },
        "resolutions": [
            "Excluded the logging endpoint from the HTTP appender's path filter.",
            "Added recursion detection in the appender; bounded depth 1.",
        ],
        "keywords": ["logging", "recursion", "appender", "disk", "infinite-loop"],
        "severity_bias": "high",
    },
    # 26. Typo in field name
    {
        "id": "typo_field",
        "titles": [
            "Field typo in {class}.{method} silently drops data",
            "Mismatched field name in {class}.{method} after rename",
        ],
        "descs": [
            "A field rename went through {service} but missed {class}.{method}. Messages with the new field were dropped at serialization; messages with the old name silently passed through.",
            "Analytics team reported 0 for a metric because {class}.{method} emitted a mistyped field name.",
        ],
        "root_causes": [
            "Typo `user_Id` vs `userId` in {class}.{method}'s JSON emitter.",
            "Field renamed in schema but not in {class}.{method}'s hand-rolled serializer.",
        ],
        "snippets_by_lang": {
            "Java": [
                "Map.of(\"user_Id\", id); // should be userId — consumer drops",
            ],
            "Python": [
                "return {\"user_Id\": id}  # consumer expects userId",
            ],
            "JavaScript": [
                "return { user_Id: id }; // typo — consumer drops field",
            ],
            "Go": [
                "return map[string]any{\"user_Id\": id} // typo",
            ],
        },
        "resolutions": [
            "Switched to a generated DTO; typo impossible.",
            "Added consumer-side strict validation on unknown/missing keys.",
        ],
        "keywords": ["typo", "field", "serialization", "schema", "rename"],
        "severity_bias": "low",
    },
    # 27. Enum drift
    {
        "id": "enum_drift",
        "titles": [
            "Enum value mismatch between {service} and {dep}",
            "Unknown enum value crashes {class}.{method}",
        ],
        "descs": [
            "{class}.{method} received an enum value {dep} had added but {service} did not know about. Deserialization threw; requests 500'd.",
            "{service} and {dep} drifted on the Status enum. {class}.{method} crashed whenever the new value was observed.",
        ],
        "root_causes": [
            "Strict enum deserializer in {class}.{method} has no default branch for unknown values.",
            "{dep} added the PENDING_REVIEW status; {service} was never updated.",
        ],
        "snippets_by_lang": {
            "Java": [
                "switch (status) {\n  case APPROVED: return ok();\n  case DECLINED: return fail();\n  // no default — new PENDING_REVIEW crashes\n}",
            ],
            "Kotlin": [
                "when (status) {\n  APPROVED -> ok()\n  DECLINED -> fail()\n  // exhaustive assumption broken when enum grows\n}",
            ],
            "Go": [
                "switch status {\ncase Approved: return ok()\ncase Declined: return fail()\n// no default\n}",
            ],
        },
        "resolutions": [
            "Added default: return unknown() branch; emit metric on unseen enum.",
            "Standardized on string-typed enums with a forward-compatible default.",
        ],
        "keywords": ["enum", "drift", "status", "switch", "deserialization"],
        "severity_bias": "low",
    },
    # 28. Migration rollback bug
    {
        "id": "migration_rollback",
        "titles": [
            "Database migration rollback leaves {service} in broken state",
            "Partial migration in {class}.{method} path after rollback",
        ],
        "descs": [
            "A Flyway migration added a column, failed on a constraint, and rolled back — but the new app code in {class}.{method} already referenced the column. Service failed to start.",
            "Rollback of {service} left the schema mid-migration. Writes via {class}.{method} hit 500s on missing column.",
        ],
        "root_causes": [
            "App + schema deployed together; rollback of app didn't include DOWN migration.",
            "Non-transactional DDL meant the failed migration left an orphaned column.",
        ],
        "snippets_by_lang": {
            "Java": [
                "@Column(name = \"ext_id\", nullable = false) // column not present after rollback\nprivate String extId;",
            ],
            "Python": [
                "class Item(Base):\n    ext_id = Column(String, nullable=False)  # missing after rollback",
            ],
            "Go": [
                "type Item struct {\n  ExtID string `db:\"ext_id\" validate:\"required\"` // missing post-rollback\n}",
            ],
        },
        "resolutions": [
            "Decoupled schema from app deploy; expand-contract pattern enforced.",
            "Added DOWN migration + test; rollout gated on migration success.",
        ],
        "keywords": ["migration", "rollback", "schema", "flyway", "ddl"],
        "severity_bias": "high",
    },
    # 29. Timeout cascade
    {
        "id": "timeout_cascade",
        "titles": [
            "Timeout cascade from {dep} saturates {class}.{method}",
            "{class}.{method} timeouts propagate to upstream callers",
        ],
        "descs": [
            "{dep} latency went from 100ms to 4s. {class}.{method}'s 5s client timeout held, but thread-pool starved; upstream callers started timing out too.",
            "Ingress requests timed out because {class}.{method} waited the full client timeout for {dep} before returning. Retries amplified it.",
        ],
        "root_causes": [
            "Client timeout on {dep} > ingress timeout on {class}.{method}; pool starvation guaranteed.",
            "No circuit breaker in front of {dep} — slow failures consumed all worker threads.",
        ],
        "snippets_by_lang": {
            "Java": [
                "RestTemplate rt = new RestTemplate();\nrt.getRequestFactory().setReadTimeout(30_000); // 30s — too generous",
            ],
            "Python": [
                "requests.get(url, timeout=30)  # 30s — too generous",
            ],
            "Go": [
                "client := &http.Client{Timeout: 30 * time.Second}",
            ],
        },
        "resolutions": [
            "Tightened {dep} client timeout to 2s; added Resilience4j circuit breaker.",
            "Enforced timeout-budget rule: client timeout < ingress timeout / 2.",
        ],
        "keywords": ["timeout", "cascade", "latency", "circuit-breaker", "starvation"],
        "severity_bias": "high",
    },
    # 30. Idempotency / duplicate processing
    {
        "id": "idempotency_missing",
        "titles": [
            "Duplicate processing in {class}.{method} on retry",
            "{class}.{method} idempotency key missing",
        ],
        "descs": [
            "Clients retrying after transient errors produced duplicate side effects in {class}.{method}. ~{impact} records affected; remediation required manual refund.",
            "At-least-once queue + no idempotency check in {class}.{method} produced duplicate emails/charges/orders.",
        ],
        "root_causes": [
            "Idempotency key generated per-attempt in {class}.{method} instead of per-order.",
            "At-least-once consumer in {class}.{method} lacks dedup on messageId.",
        ],
        "snippets_by_lang": {
            "Java": [
                "String idemKey = UUID.randomUUID().toString(); // new per attempt",
            ],
            "Kotlin": [
                "val idemKey = UUID.randomUUID().toString() // re-created on retry",
            ],
            "Python": [
                "idem_key = str(uuid.uuid4())  # new every attempt",
            ],
            "Go": [
                "idemKey := uuid.NewString() // not stable across retries",
            ],
            "JavaScript": [
                "const idemKey = crypto.randomUUID(); // fresh per attempt",
            ],
        },
        "resolutions": [
            "Derived idempotency key from (orderId, attempt-bucket).",
            "Added Redis-backed dedup on messageId consumer-side.",
        ],
        "keywords": ["idempotency", "duplicate", "retry", "dedup", "at-least-once"],
        "severity_bias": "med",
    },
    # 31. Kafka consumer lag / partition
    {
        "id": "consumer_lag",
        "titles": [
            "Kafka consumer lag on {service} from {class}.{method} bottleneck",
            "{service} fell behind topic by {impact} messages",
        ],
        "descs": [
            "{service} consumer lag grew to {impact} messages. {class}.{method}'s per-message processing time exceeded poll interval; single-partition concurrency starved.",
            "A sudden traffic surge overwhelmed the single-threaded consumer in {class}.{method}; lag alerts fired within 10 minutes.",
        ],
        "root_causes": [
            "Consumer concurrency set to 1; topic has 8 partitions but only 1 worker thread.",
            "Per-message processing in {class}.{method} includes an expensive ML call; latency > poll interval.",
        ],
        "snippets_by_lang": {
            "Java": [
                "@KafkaListener(topics = [\"product-updates\"], concurrency = \"1\")\npublic void consume(Record r) { process(r); }",
            ],
            "Kotlin": [
                "@KafkaListener(topics = [\"product-updates\"], concurrency = \"1\")\nfun consume(r: Record) { process(r) }",
            ],
            "Python": [
                "consumer = KafkaConsumer('product-updates')  # default single-threaded\nfor msg in consumer:\n    process(msg)",
            ],
            "Go": [
                "for msg := range claim.Messages() {\n  process(msg) // single-threaded claim handler\n}",
            ],
        },
        "resolutions": [
            "Increased consumer concurrency to 8; matched topic partition count.",
            "Moved ML call off hot path; per-message processing now <20ms.",
        ],
        "keywords": ["kafka", "lag", "consumer", "partition", "concurrency"],
        "severity_bias": "med",
    },
    # 32. Cold start / slow warmup
    {
        "id": "cold_start",
        "titles": [
            "Cold-start latency in {class}.{method} on {service} autoscale",
            "{class}.{method} first-request latency crosses SLO",
        ],
        "descs": [
            "New pods on {service} served p99 > 8s for first 60 seconds. JIT + cache warmup missing in {class}.{method}.",
            "Post-deploy, cold-started instances of {service} handled traffic before {class}.{method} had loaded its models.",
        ],
        "root_causes": [
            "No readiness-probe warmup in {service}; autoscaler-added pods received traffic immediately.",
            "{class}.{method} lazily loads models on first request; initialization takes 40s.",
        ],
        "snippets_by_lang": {
            "Java": [
                "// no warmup queries on pod start\n@PostConstruct public void init() { /* nothing */ }",
            ],
            "Go": [
                "// readiness probe returns OK as soon as port binds — no warmup",
            ],
            "Python": [
                "# model loaded lazily on first request\n_model = None\ndef predict(x):\n    global _model\n    if _model is None: _model = load_model()  # 40s\n    return _model(x)",
            ],
        },
        "resolutions": [
            "Added 30s warmup phase with representative query replay on readiness probe.",
            "Eager-loaded models in init; readiness probe blocks until ready.",
        ],
        "keywords": ["cold-start", "warmup", "autoscale", "readiness", "latency"],
        "severity_bias": "low",
    },
    # 33. JWT / auth drift
    {
        "id": "jwt_drift",
        "titles": [
            "JWT verification drift in {class}.{method}",
            "Token validation weakened in {class}.{method}",
        ],
        "descs": [
            "{class}.{method} accepted expired tokens for ~{impact} minutes. Clock skew leeway widened by accident.",
            "JWT signature verification in {class}.{method} fell back to HS256 when RS256 key lookup failed.",
        ],
        "root_causes": [
            "Leeway changed from 30s to 3600s in {class}.{method} via a \"debug fix\" that was never reverted.",
            "Algorithm confusion — accepted HS256 tokens signed with the public RS256 key.",
        ],
        "snippets_by_lang": {
            "Java": [
                "verifier.acceptLeeway(3600); // was 30, changed by mistake",
            ],
            "Kotlin": [
                "JWT.require(algorithm).acceptLeeway(3600).build() // too permissive",
            ],
            "Python": [
                "jwt.decode(token, key, algorithms=[\"RS256\", \"HS256\"])  # algorithm confusion",
            ],
            "Go": [
                "jwt.Parse(token, func(t *jwt.Token) (interface{}, error) { return key, nil }) // trusts alg header",
            ],
        },
        "resolutions": [
            "Reverted leeway to 30s; unit test asserts < 60s.",
            "Pinned algorithm to RS256 explicitly; rejected unexpected algs.",
        ],
        "keywords": ["jwt", "auth", "expiry", "leeway", "algorithm", "verification"],
        "severity_bias": "high",
    },
    # 34. Goroutine / thread leak
    {
        "id": "goroutine_leak",
        "titles": [
            "Goroutine leak in {class}.{method} eats memory",
            "Thread leak from {class}.{method} saturates {service}",
        ],
        "descs": [
            "{service} goroutine count grew to 120k over 6 hours. {class}.{method} spawned a goroutine per request but never drained the blocking channel.",
            "A thread-pool executor in {class}.{method} spawned threads but its submitted tasks never completed.",
        ],
        "root_causes": [
            "Unbuffered channel in {class}.{method}; goroutine blocks on send when receiver exits early.",
            "CompletableFuture in {class}.{method} is never completed on the cancellation path.",
        ],
        "snippets_by_lang": {
            "Go": [
                "func handle(req *Req) {\n  ch := make(chan *Resp) // unbuffered\n  go func() { ch <- process(req) }() // leaks if nobody reads\n  select {\n    case r := <-ch: return r\n    case <-ctx.Done(): return nil // goroutine still blocked on send\n  }\n}",
            ],
            "Java": [
                "exec.submit(() -> longRunning()); // never checked; if pool is bounded, piles up",
            ],
        },
        "resolutions": [
            "Switched to buffered channel; guaranteed drain on ctx.Done().",
            "Switched to virtual threads; added metric for thread count.",
        ],
        "keywords": ["goroutine", "thread", "leak", "channel", "pool"],
        "severity_bias": "med",
    },
    # 35. Config drift / secret rotation
    {
        "id": "config_drift",
        "titles": [
            "Stale config in {class}.{method} after secret rotation",
            "{service} not reloaded after config change",
        ],
        "descs": [
            "A secret rotation for {dep} went through everywhere except {service}. {class}.{method} continued using the old credential; auth failures started 15 minutes later.",
            "{class}.{method} cached the config at boot; operators rotated the flag but {service} wasn't restarted.",
        ],
        "root_causes": [
            "{class}.{method} reads the secret once at boot; no refresh hook.",
            "Feature-flag SDK polling interval is 1 hour; {class}.{method} missed the flip.",
        ],
        "snippets_by_lang": {
            "Java": [
                "@Value(\"${api.key}\") private String apiKey; // resolved once at boot",
            ],
            "Python": [
                "API_KEY = os.getenv('API_KEY')  # read at import, never refreshed",
            ],
            "Go": [
                "var apiKey = os.Getenv(\"API_KEY\") // package init, never re-read",
            ],
        },
        "resolutions": [
            "Moved secret lookup inside the request path; fetched from Vault per-minute cache.",
            "Added SIGHUP handler that reloads config.",
        ],
        "keywords": ["config", "secret", "rotation", "refresh", "boot", "drift"],
        "severity_bias": "high",
    },
]

EVENT_TYPES = ["metric_spike", "alert_fired", "span_error", "deploy", "saturation_warning", "slow_query"]
SEVERITY_BY_TYPE = {
    "metric_spike":       ["warn", "error"],
    "alert_fired":        ["warn", "error", "critical"],
    "span_error":         ["error"],
    "deploy":             ["info"],
    "saturation_warning": ["warn"],
    "slow_query":         ["warn", "error"],
}

SEV_DISTRIBUTION = {
    # bias → (SEV-1 weight, SEV-2 weight, SEV-3 weight)
    "high": (0.12, 0.38, 0.50),
    "med":  (0.04, 0.26, 0.70),
    "low":  (0.01, 0.14, 0.85),
}

# A dep pool used when snippets/templates reference {dep}.
COMMON_DEPS = ["stripe", "postgres", "redis", "kafka", "elasticsearch", "s3", "dynamo", "sqs", "rabbitmq", "snowflake"]


def iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def stable_hex(rng: random.Random, prefix: str, length: int = 7) -> str:
    """Deterministic hex sha prefix from the rng's current state and a salt."""
    salt = rng.getrandbits(64)
    h = hashlib.sha1(f"{prefix}:{salt}".encode()).hexdigest()
    return h[:length]


def pick_service(rng: random.Random) -> dict:
    names = [s["name"] for s in SERVICES]
    weights = [SERVICE_INCIDENT_WEIGHTS[n] for n in names]
    chosen = rng.choices(names, weights=weights, k=1)[0]
    return next(s for s in SERVICES if s["name"] == chosen)


def pick_method_for_service(rng: random.Random, service_name: str) -> dict | None:
    service_methods = [m for m in METHODS_OF_INTEREST if m["service"] == service_name]
    if service_methods and rng.random() < 0.55:
        return rng.choice(service_methods)
    return None


def synth_class_method(rng: random.Random, service_name: str) -> tuple[str, str, str, int]:
    """Return (class_name, method_name, file_path, line_number) for a synthetic, service-aligned method."""
    # Prefer a method-of-interest 55% of the time so the demo-critical methods appear frequently.
    moi = pick_method_for_service(rng, service_name)
    if moi is not None:
        cls, meth = moi["fq_name"].split(".", 1)
        return cls, meth, moi["file"], moi["line"] + rng.randint(-5, 5)

    svc = next(s for s in SERVICES if s["name"] == service_name)
    lang = svc["language"]
    base_dir = svc["repo_path"]
    # Synthesize class/method names with service-appropriate prefixes
    service_prefix = "".join(w.capitalize() for w in service_name.replace("-service", "").split("-"))
    class_pool = [
        f"{service_prefix}Service",
        f"{service_prefix}Controller",
        f"{service_prefix}Client",
        f"{service_prefix}Repository",
        f"{service_prefix}Handler",
        f"{service_prefix}Processor",
        f"{service_prefix}Worker",
        f"{service_prefix}Gateway",
        f"{service_prefix}Scheduler",
        f"{service_prefix}Resolver",
    ]
    method_pool = [
        "process", "handle", "execute", "dispatch", "persist", "lookup", "resolve",
        "validate", "verify", "enrich", "aggregate", "emit", "consume", "poll",
        "refresh", "reserve", "release", "commit", "rollback", "authenticate",
        "authorize", "render", "build", "serialize", "deserialize", "fetch",
        "apply", "match", "index", "cache",
    ]
    cls = rng.choice(class_pool)
    meth = rng.choice(method_pool)

    if lang == "Java":
        pkg = service_name.replace("-service", "").replace("-", "")
        file_path = f"{base_dir}src/main/java/com/swagstore/{pkg}/{cls}.java"
    elif lang == "Kotlin":
        pkg = service_name.replace("-service", "").replace("-", "")
        file_path = f"{base_dir}src/main/kotlin/com/swagstore/{pkg}/{cls}.kt"
    elif lang == "Python":
        pkg = service_name.replace("-service", "_service").replace("-", "_")
        file_name = f"{cls[0].lower() + cls[1:]}.py"
        file_path = f"{base_dir}src/{pkg}/{file_name}"
    elif lang == "Go":
        pkg = service_name.replace("-service", "").replace("-", "")
        file_path = f"{base_dir}internal/{pkg}/{cls.lower()}.go"
    else:
        file_path = f"{base_dir}src/{cls}.js"

    line = rng.randint(20, 350)
    return cls, meth, file_path, line


def pick_snippet_for_archetype(rng: random.Random, archetype: dict, language: str) -> str:
    snippets = archetype["snippets_by_lang"].get(language)
    if not snippets:
        # Fallback: pick any language snippet available
        for lang_fallback in ("Java", "Python", "Go", "JavaScript", "Kotlin"):
            snippets = archetype["snippets_by_lang"].get(lang_fallback)
            if snippets:
                break
    return rng.choice(snippets)


def render_template(template: str, ctx: dict) -> str:
    """str.format with missing-key tolerance."""
    class SafeDict(dict):
        def __missing__(self, key): return "{" + key + "}"
    return template.format_map(SafeDict(ctx))


def synth_incident(rng: random.Random, idx: int) -> dict:
    svc = pick_service(rng)
    service_name = svc["name"]
    language = svc["language"]
    archetype = rng.choice(ARCHETYPES)
    cls, method, file_path, line = synth_class_method(rng, service_name)

    # Pick a realistic dep from the service's own deps first, else common pool
    dep = rng.choice(svc["deps"]) if svc["deps"] and rng.random() < 0.55 else rng.choice(COMMON_DEPS)
    entity_pool = ["order", "item", "cart", "shipment", "payment", "reservation", "record", "session", "token", "event"]
    field_pool = ["userId", "billingAddress.country", "customer.email", "shipping.region", "metadata.tenantId", "config.flags", "profile.tier", "tracking.id"]
    condition_pool = ["partial-order", "retry", "fallback", "cold-start", "replay", "edge"]

    # ISO/TZ date within 18-month window
    seconds_range = int((WINDOW_END - NARRATIVE_WINDOW_START).total_seconds())
    opened_at = NARRATIVE_WINDOW_START + timedelta(seconds=rng.randint(0, seconds_range))

    # severity per bias
    sev_bias = archetype.get("severity_bias", "low")
    sev_weights = SEV_DISTRIBUTION[sev_bias]
    severity = rng.choices(["SEV-1", "SEV-2", "SEV-3"], weights=sev_weights, k=1)[0]

    # status
    is_open = rng.random() < 0.15
    status = "open" if is_open else "resolved"
    resolved_at = None
    if not is_open:
        resolve_window_hours = rng.choice([0.5, 1, 2, 4, 8, 12, 24, 48])
        resolved_at = opened_at + timedelta(hours=resolve_window_hours, minutes=rng.randint(0, 59))

    ctx = {
        "service": service_name,
        "class": cls,
        "method": method,
        "field": rng.choice(field_pool),
        "entity": rng.choice(entity_pool),
        "condition": rng.choice(condition_pool),
        "dep": dep,
        "deps": ", ".join(svc["deps"][:2]) if svc["deps"] else "downstream services",
        "impact": rng.choice([12, 40, 120, 300, 500, 800, 1500, 2400, 3800]),
        "file": file_path,
        "line": line,
        "commit": stable_hex(rng, f"commit-{idx}"),
    }

    title = render_template(rng.choice(archetype["titles"]), ctx)
    description = render_template(rng.choice(archetype["descs"]), ctx)
    rc_raw = render_template(rng.choice(archetype["root_causes"]), ctx)
    snippet = pick_snippet_for_archetype(rng, archetype, language)

    offending_methods = [f"{cls}.{method}"]
    # 25% chance of touching a second method (blast radius)
    if rng.random() < 0.25:
        cls2, meth2, _, _ = synth_class_method(rng, service_name)
        second = f"{cls2}.{meth2}"
        if second not in offending_methods:
            offending_methods.append(second)

    keywords_pool = list(archetype["keywords"])
    # Add some incidental keywords derived from context
    extra_pool = [service_name.split("-")[0], method.lower(), cls.lower(), dep]
    rng.shuffle(extra_pool)
    keywords = sorted(set(keywords_pool + extra_pool[:rng.randint(1, 3)]))[:10]

    incident_id = f"INC-{5000 + idx:04d}"

    incident = {
        "id": incident_id,
        "service": service_name,
        "status": status,
        "severity": severity,
        "opened_at": iso(opened_at),
        "resolved_at": iso(resolved_at) if resolved_at else None,
        "title": title,
        "description": description,
        "offending_commit": ctx["commit"],
        "offending_file": file_path,
        "offending_methods": offending_methods,
        "offending_code_snippet": snippet,
        "keywords": keywords,
    }
    if status == "resolved":
        incident["root_cause"] = rc_raw
        incident["resolution"] = render_template(rng.choice(archetype["resolutions"]), ctx)
    else:
        incident["root_cause_hypothesis"] = rc_raw
        incident["resolution"] = None

    return incident


def build_incidents(rng: random.Random, count: int) -> list[dict]:
    incidents = list(DEMO_INCIDENTS)
    remaining = max(0, count - len(DEMO_INCIDENTS))
    for i in range(remaining):
        incidents.append(synth_incident(rng, i))
    # Ensure unique ids
    seen_ids = set()
    for inc in incidents:
        assert inc["id"] not in seen_ids, f"duplicate id {inc['id']}"
        seen_ids.add(inc["id"])
    return incidents


def generate_events(rng: random.Random, incidents: list[dict], target_count: int) -> list[dict]:
    events: list[dict] = []
    service_by_name = {s["name"]: s for s in SERVICES}
    service_names = [s["name"] for s in SERVICES]
    service_weights = [s["weight"] for s in SERVICES]
    methods_by_service: dict[str, list[dict]] = {}
    for m in METHODS_OF_INTEREST:
        methods_by_service.setdefault(m["service"], []).append(m)

    # 1) Incident-clustered events for incidents within the event window
    for inc in incidents:
        opened_at = datetime.fromisoformat(inc["opened_at"].replace("Z", "+00:00"))
        if opened_at < EVENT_WINDOW_START or opened_at > WINDOW_END:
            # Only cluster events for incidents within the operational lookback window
            continue
        cluster_size = rng.randint(4, 14)
        for _ in range(cluster_size):
            offset = timedelta(minutes=rng.randint(-120, 120))
            ts = opened_at + offset
            if ts < EVENT_WINDOW_START or ts > WINDOW_END:
                continue
            etype = rng.choices(EVENT_TYPES, weights=[4, 4, 3, 1, 2, 2])[0]
            sev = rng.choice(SEVERITY_BY_TYPE[etype])
            svc = inc["service"]
            method = inc["offending_methods"][0] if inc["offending_methods"] else None
            base_err = service_by_name[svc]["base_error_rate"]
            events.append({
                "id": f"EVT-{len(events) + 1:05d}",
                "timestamp": iso(ts),
                "type": etype,
                "service": svc,
                "method": method,
                "severity": sev,
                "metric_name": rng.choice(["error_rate", "latency_p99_ms", "saturation"]) if etype != "deploy" else None,
                "metric_value": round(rng.uniform(base_err * 3, base_err * 12), 4) if etype in ("metric_spike", "alert_fired") else None,
                "threshold": round(base_err * 2, 4) if etype in ("metric_spike", "alert_fired") else None,
                "message": f"{etype.replace('_', ' ').title()} during incident window of {inc['id']}",
                "related_incident_id": inc["id"],
            })

    # 2) Baseline noise events across 30-day window, weighted by service request volume
    while len(events) < target_count:
        ts = EVENT_WINDOW_START + timedelta(seconds=rng.randint(0, int((WINDOW_END - EVENT_WINDOW_START).total_seconds())))
        svc_name = rng.choices(service_names, weights=service_weights)[0]
        svc = service_by_name[svc_name]
        etype = rng.choices(EVENT_TYPES, weights=[3, 3, 2, 2, 2, 1])[0]
        sev = rng.choice(SEVERITY_BY_TYPE[etype])
        method = None
        if etype in ("span_error", "metric_spike", "slow_query") and svc_name in methods_by_service:
            method = rng.choice(methods_by_service[svc_name])["fq_name"]
        events.append({
            "id": f"EVT-{len(events) + 1:05d}",
            "timestamp": iso(ts),
            "type": etype,
            "service": svc_name,
            "method": method,
            "severity": sev,
            "metric_name": rng.choice(["error_rate", "latency_p99_ms", "saturation"]) if etype != "deploy" else None,
            "metric_value": round(rng.uniform(svc["base_error_rate"] * 0.5, svc["base_error_rate"] * 4), 4) if etype in ("metric_spike", "alert_fired") else None,
            "threshold": round(svc["base_error_rate"] * 2, 4) if etype in ("metric_spike", "alert_fired") else None,
            "message": {
                "metric_spike": "Metric crossed warning band",
                "alert_fired": "Monitor alerted",
                "span_error": "Distributed trace span marked errored",
                "deploy": f"Version {rng.randint(1, 9)}.{rng.randint(0, 40)}.{rng.randint(0, 30)} deployed",
                "saturation_warning": "Resource saturation above 80%",
                "slow_query": f"Query exceeded {rng.randint(500, 3000)}ms threshold",
            }[etype],
            "related_incident_id": None,
        })

    events.sort(key=lambda e: e["timestamp"])
    for i, e in enumerate(events, start=1):
        e["id"] = f"EVT-{i:05d}"
    return events


def enrich_services(events: list[dict], incidents: list[dict]) -> list[dict]:
    by_service: dict[str, list[dict]] = {}
    for e in events:
        by_service.setdefault(e["service"], []).append(e)
    enriched = []
    for s in SERVICES:
        svc_events = by_service.get(s["name"], [])
        error_like = [e for e in svc_events if e["severity"] in ("error", "critical")]
        recent_window = WINDOW_END - timedelta(hours=1)
        error_rate_1h = sum(1 for e in error_like if datetime.fromisoformat(e["timestamp"].replace("Z", "+00:00")) > recent_window) / max(1, s["request_volume_1h"] / 1000)
        enriched.append({
            "name": s["name"],
            "team": s["team"],
            "language": s["language"],
            "repo_path": s["repo_path"],
            "direct_dependencies": s["deps"],
            "direct_dep_count": len(s["deps"]) + max(0, int(s["weight"])),
            "error_rate_1h": round(max(error_rate_1h, s["base_error_rate"]), 4),
            "error_rate_7d_baseline": s["base_error_rate"],
            "error_rate_delta_vs_last_commit": round(max(error_rate_1h, s["base_error_rate"]) / s["base_error_rate"], 2),
            "change_velocity_7d": len([e for e in svc_events if e["type"] == "deploy" and datetime.fromisoformat(e["timestamp"].replace("Z", "+00:00")) > WINDOW_END - timedelta(days=7)]),
            "deploy_window_today": s["name"] in {"payment-service", "inventory-service", "cart-service"},
            "on_call_rotation": s["on_call"],
            "sla_target": s["sla"],
            "request_volume_1h": s["request_volume_1h"],
            "event_count_30d": len(svc_events),
            "open_incident_count": sum(1 for inc in incidents if inc["service"] == s["name"] and inc["status"] == "open"),
        })
    return enriched


def enrich_methods(events: list[dict], incidents: list[dict]) -> list[dict]:
    enriched = []
    for m in METHODS_OF_INTEREST:
        linked = [inc["id"] for inc in incidents if m["fq_name"] in inc["offending_methods"]]
        event_count = sum(1 for e in events if e["method"] == m["fq_name"])
        enriched.append({
            **m,
            "linked_incidents": linked,
            "linked_events_30d": event_count,
        })
    return enriched


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--incidents", type=int, default=DEFAULT_INCIDENT_COUNT,
                        help="total incident count (default: %(default)s; includes hand-crafted demo incidents)")
    return parser.parse_args()


def main():
    args = parse_args()
    rng = random.Random(SEED)

    incidents = build_incidents(rng, args.incidents)
    target_events = max(2200, len(incidents) * EVENT_PER_INCIDENT_RATIO)
    events = generate_events(rng, incidents, target_events)
    services = enrich_services(events, incidents)
    methods = enrich_methods(events, incidents)

    out = {
        "meta": {
            "generated_at": iso(WINDOW_END),
            "generator_version": "2.0.0",
            "seed": SEED,
            "window_start": iso(EVENT_WINDOW_START),
            "window_end": iso(WINDOW_END),
            "narrative_window_start": iso(NARRATIVE_WINDOW_START),
            "service_count": len(services),
            "incident_count": len(incidents),
            "event_count": len(events),
            "method_count": len(methods),
            "archetype_count": len(ARCHETYPES),
            "source": "hand-authored demo narratives (4) + archetype-generated narratives (from 35 root-cause patterns) + algorithmic event synthesis",
        },
        "services": services,
        "incidents": incidents,
        "methods_of_interest": methods,
        "events": events,
    }

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_PATH.open("w") as f:
        json.dump(out, f, indent=2)

    print(f"[ok] wrote {OUT_PATH}")
    print(f"     services={len(services)} incidents={len(incidents)} methods={len(methods)} events={len(events)} archetypes={len(ARCHETYPES)}")


if __name__ == "__main__":
    main()

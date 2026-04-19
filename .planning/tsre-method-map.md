# tsre-microservices method catalog

**Investigated:** 2026-04-18 (Phase 2 prep)
**Outcome:** Shrink `methods_of_interest` from 15 fictional names → 7 real Java methods. Update Decision Log in CLAUDE.md.

## Reality check

tsre-microservices is a polyglot demo repo. Only TWO services contain Java:

| Service | Language | Gutter-eligible? |
|---|---|---|
| paymentservice | Java (Spring Boot + gRPC) | ✅ |
| adservice | Java (gRPC) | ✅ |
| checkoutservice | Go | ❌ (LineMarkerProvider is `language="JAVA"`) |
| currencyservice | Node.js | ❌ |
| emailservice | Python | ❌ |
| frontend | Go | ❌ |
| inventoryservice | Python | ❌ |
| loadgenerator | Node.js | ❌ |
| loggenerator | Python | ❌ |
| productcatalogservice | Go | ❌ |
| recommendationservice | Python | ❌ |
| shippingservice | Go | ❌ |
| cartservice | — (empty) | ❌ |
| paymentdbservice | — (empty) | ❌ |

Our plugin's `<codeInsight.lineMarkerProvider language="JAVA">` only fires on Java files. Extending to Go/Python would require depending on their IJ plugins (not bundled in IC), which adds dep risk and buys us little for this demo.

## Decision

**Scope down to paymentservice + adservice for Phase 2.** Seven real methods cover the full demo narrative (regression beat, fix-confirmation beat, and a cross-service dep beat).

## High-risk candidates (7, ordered by demo value)

| fq_name | Real file path | Package | Line | Demo role |
|---|---|---|---|---|
| `PaymentServiceImpl.charge` | src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentServiceImpl.java | com.hipstershop.paymentservicejava | 29 | **HIGH** — gRPC charge entrypoint; stars in scenarios A (regression of INC-4402 null-check) + C (fix-confirmation of INC-4417 retry backoff) |
| `PaymentController.clearPayment` | src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentController.java | com.hipstershop.paymentservicejava | 28 | **MEDIUM** — REST endpoint, touches DB via `PaymentRecordRepository`; good "touches offending_file" signal for retrieval |
| `AdService.getAds` | src/adservice/src/main/java/hipstershop/AdService.java | hipstershop | 95 | **MEDIUM** — second Java service; cross-service blast-radius demo |
| `AdService.ad_analytics` | src/adservice/src/main/java/hipstershop/AdService.java | hipstershop | 143 | **MEDIUM** — repurposed from fictional `InventoryService.reserve` for scenario B (race/concurrency) |
| `PrometheusHealthResource.postStatus` | src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PrometheusHealthResource.java | com.hipstershop.paymentservicejava | 54 | **LOW** — health check, nice "this looks harmless but it isn't" miss for the tooltip |
| `AdServiceClient.getAds` | src/adservice/src/main/java/hipstershop/AdServiceClient.java | hipstershop | 60 | **LOW** — demonstrates the gutter working on client-side code too |
| `PaymentservicejavaApplication.initializeBackupScheduler` | src/paymentservice/src/main/java/com/hipstershop/paymentservicejava/PaymentservicejavaApplication.java | com.hipstershop.paymentservicejava | — | **LOW** — Spring Boot startup; rarely edited, good "no gutter noise" control |

## Scenario remapping (ARCHITECTURE.md §3.2)

| Scenario | Original target | New target | Still valid? |
|---|---|---|---|
| A. INC-4402 regression (null-check) | `PaymentService.charge` | `PaymentServiceImpl.charge` | ✅ unchanged narrative |
| B. INC-4388 race | `InventoryService.reserve` | `AdService.ad_analytics` | ⚠ narrative must shift from inventory race → analytics aggregation race |
| C. INC-4417 fix-confirmation | `PaymentService.charge` (retry backoff) | `PaymentServiceImpl.charge` (same) | ✅ unchanged |

## Package collisions

None. All 8 simple class names are unique across packages.

## Notes

- Simple class name (not FQ with package) is what our `LineMarkerProvider` matches on (`fq_name = ClassName.methodName`). Unique simple names = no ambiguity.
- Both Java services use gRPC — `PaymentServiceImpl.charge` is annotated with `@Override` overriding `PaymentServiceGrpc.PaymentServiceImplBase.charge`. The gutter matches on the overriding impl method.
- If a judge opens a Go/Python file during the demo, the gutter silently no-ops (correct behavior; `LineMarkerProvider` `language="JAVA"` just doesn't run).

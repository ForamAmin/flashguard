# FlashGuard — Full Build Documentation

**Project:** Overselling-proof claim engine for limited-slot drops
**Stack:** Spring Boot, PostgreSQL, Redis, Docker, k6
**Status as of this doc:** Parts 1–8 complete (Foundation → Failure Handling). Remaining: Part 9 (Dockerize app), Part 10 (final polish/docs), optional demo frontend.

---

## 1. What FlashGuard Is

FlashGuard is a standalone, embeddable backend infrastructure service that guarantees a limited inventory can never be oversold, even when thousands of requests hit it at the same instant.

It is **not** a full ticketing platform. A hypothetical customer — referred to throughout as **Jalsa** — already owns its website, users, payments, orders, and ticket generation. Jalsa integrates FlashGuard only for the high-concurrency "claim" moment: the few seconds where a limited number of slots must be handed out fairly and without overselling.

**Ownership split:**

| FlashGuard owns | Jalsa (customer) owns |
|---|---|
| Organizations & API credentials | Customers |
| Drops (limited-slot inventory) | Website / frontend |
| Claim concurrency & correctness | Customer authentication |
| Claim records | Payments |
| Overselling prevention | Orders |
| Metrics / observability | Tickets, emails, SMS |

FlashGuard is multi-tenant: many Organizations can use the same FlashGuard deployment, each fully isolated from the others' data.

---

## 2. Core Problem Being Solved

A naive claim flow:

```
read inventory
if inventory > 0:
    inventory = inventory - 1
```

Under concurrency, two simultaneous requests can both read `inventory = 1`, both pass the check, and both decrement — overselling by one unit. At 10,000 concurrent requests against 100 slots, this failure compounds constantly, not rarely.

FlashGuard's core guarantee, proven empirically in Part 7:

> No matter how many concurrent claim requests arrive, FlashGuard must never allow more successful claims than the configured inventory.

This is achieved by making the "check and decrement" a single **atomic** operation in Redis (via a Lua script), so no two requests can ever interleave between the check and the decrement.

---

## 3. High-Level Architecture

```
USERS (thousands, concurrent)
        │
        ▼
JALSA WEBSITE (frontend + backend)
        │  Authorization: Bearer <API_KEY>
        ▼
FLASHGUARD (Spring Boot API)
        │
        ├── ApiKeyAuthFilter (authentication)
        ├── Ownership checks (authorization / multi-tenancy)
        ▼
   Claim Engine (ClaimService + InventoryService)
        │
        ▼
 ┌──────────────┐        ┌──────────────┐
 │    REDIS     │        │  POSTGRESQL  │
 │ Hot inventory│        │ Durable data │
 │ Atomic claim │        │ Orgs/Drops/  │
 │ (Lua script) │        │ Claims       │
 └──────────────┘        └──────────────┘
        │
        ▼
 SUCCESS / SOLD_OUT  →  back to Jalsa  →  Jalsa handles payment, order, ticket
```

**Why two data stores:**
- **Redis** — the hot path. Extremely fast, supports atomic scripted operations, holds the live per-drop inventory counter (`drop:{id}:inventory`). This is where the "can I claim?" decision is made.
- **PostgreSQL** — the durable system of record. Stores organizations, drops, and claim records for anything that needs to survive restarts, be queried later, or audited. Answers "what happened?" rather than "can I claim right now?"

The claim response is **synchronous** — FlashGuard returns `SUCCESS` or `SOLD_OUT` immediately in the HTTP response body, not via polling or async callback.

---

## 4. Build Order (Roadmap)

1. **Foundation** — Spring Boot, Docker, Postgres, Redis ✅
2. **Database model** — Organization, Drop, Claim entities/tables ✅
3. **Authentication** — API-key mechanism ✅
4. **Drop APIs** — create/read, plus multi-tenant ownership checks ✅
5. **Redis inventory** — live counter per drop ✅
6. **Claim engine** — atomic Redis Lua logic ✅
7. **Concurrency load test** — k6, proved zero overselling ✅
8. **Failure handling** — Redis/Postgres inconsistency handling, self-heal, retries ✅
9. **Dockerize the app itself** — ⏳ next
10. **Final documentation** — ⏳ next

---

## 5. Part 1 — Foundation

**Stack confirmed working:** Java 22, Spring Boot (Maven), IntelliJ IDEA, PostgreSQL (postgres:16-alpine) and Redis (redis:7.4-alpine), both via Docker Compose with health checks and `unless-stopped` restart policies.

**docker-compose.yml (current):**
```yaml
services:
  redis:
    image: redis:7.4-alpine
    container_name: flashguard-redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - flashguard_redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  postgres:
    image: postgres:16-alpine
    container_name: flashguard-postgres
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    ports:
      - "5432:5432"
    volumes:
      - flashguard_postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  flashguard_redis_data:
  flashguard_postgres_data:
```

Credentials are supplied via a `.env` file (git-ignored), not hardcoded — a deliberate improvement made when this project was rebuilt as a fresh IntelliJ project partway through development (see §5.1).

**application.properties:**
```properties
spring.application.name=flashguard
server.port=8080

spring.datasource.url=jdbc:postgresql://localhost:5432/flashguard
spring.datasource.username=flashguard
spring.datasource.password=flashguard

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.jdbc.time_zone=Asia/Kolkata

spring.data.redis.host=localhost
spring.data.redis.port=6379
```

### 5.1 Issues hit and fixed during Foundation

**A) Postgres timezone startup failure.**
Symptom: `FATAL: invalid value for parameter "TimeZone": "Asia/Calcutta"`.
Cause: the PostgreSQL JDBC driver sends the JVM's default timezone during the initial connection handshake, before Hibernate's own `hibernate.jdbc.time_zone` property is applied. On this Windows machine the JVM's default resolved to the legacy IANA alias `Asia/Calcutta`, which the Postgres version in use rejects.
Fix: force the JVM's default timezone explicitly via a startup flag: `-Duser.timezone=Asia/Kolkata` (IntelliJ Run/Debug Configurations → VM options).

**B) Full project rebuild after a stale Postgres volume/password mismatch.**
The original project's Postgres container had a persistent volume from an earlier, different password configuration. Postgres only runs its user/password initialization on a truly empty volume — restarting or reconfiguring the container afterward doesn't re-apply new credentials, so the app failed with `password authentication failed for user "flashguard"` even though `application.properties` and `docker-compose.yml` agreed with each other. Diagnosed by directly testing credentials against the container (`psql -U flashguard`), bypassing Spring Boot entirely, to isolate whether the problem was in the container or the app config.
Resolution: rather than continue fighting the old container, the project was rebuilt as a fresh IntelliJ project with an improved `docker-compose.yml` (env-var indirection via `.env`, health checks, restart policies) — a strict upgrade over the original setup, not just a reset.

**C) Spring Security absence, not presence, caused early 401s (new project).**
Unlike the original project (which had `spring-boot-starter-security` on the classpath and hit Spring Security's default deny-all), the rebuilt project's `401 Unauthorized` responses on `/health/redis` and `/v1/organizations` were actually coming from **`ApiKeyAuthFilter` itself** — a plain `OncePerRequestFilter`, entirely independent of Spring Security. Confirmed by attempting to add a `SecurityConfig` bean, which failed to compile with `package org.springframework.security... does not exist`, proving the dependency wasn't present at all in this project. Resolution: no Spring Security dependency was added back; instead, `ApiKeyAuthFilter`'s existing path-exclusion pattern (already used for `/v1/organizations`) was extended to also exclude `/health`. This is a simpler, leaner final design than the original project's — one less dependency, one less moving part, since Spring Security's only role in the original project was plumbing for a filter that works fine on its own.

---

## 6. Part 2 — Database Model

**Package structure:**
```
com.flashguard
├── entity        (Organization, Drop, DropStatus, Claim, ClaimStatus)
├── repository    (OrganizationRepository, DropRepository, ClaimRepository)
├── dto           (request/response records)
├── controller    (HTTP layer)
├── service       (business logic: ApiKeyService, InventoryService, DropService, ClaimService)
└── config        (ApiKeyAuthFilter)
```

**Design decisions:**

- **IDs are UUIDs, not auto-increment Long.** `organizationId`/`dropId`/`claimId` are exposed in public API responses to external customers. Sequential integer IDs leak how many records exist and are trivially guessable. UUIDs are standard for public-facing resource identifiers.
- **API keys are never stored raw.** Only a SHA-256 hash (`apiKeyHash`) is persisted, mirroring password-storage practice. The raw key is shown to the customer exactly once, at creation time.
- **`@ManyToOne` relationships** (not raw UUID foreign-key columns) between `Drop → Organization` and `Claim → Drop`, letting Hibernate manage the joins — the idiomatic JPA approach at this scale.
- **`SOLD_OUT` claim attempts are persisted as `Claim` rows**, not discarded — a deliberate tradeoff, since rejection-rate metrics (an explicit resume goal) cannot be computed without a record of rejected attempts. Cost: extra write volume on the hot path, which Part 7's load test later surfaced as a real latency contributor (see §11).
- **`organizationId` was not duplicated onto `Claim`** — reachable via `claim.getDrop().getOrganization().getId()`. Avoiding premature denormalization.

**Entities:**

| Entity | Key fields |
|---|---|
| `Organization` | `id` (UUID), `name`, `apiKeyHash`, `createdAt` |
| `Drop` | `id` (UUID), `organization` (→ Organization), `name`, `totalInventory`, `status` (`DRAFT`/`ACTIVE`/`ENDED`), `createdAt` |
| `Claim` | `id` (UUID), `drop` (→ Drop), `customerReference`, `status` (`SUCCESS`/`SOLD_OUT`), `createdAt` |

**Verified:** with `ddl-auto=update`, Hibernate auto-created all three tables against the real Postgres instance, confirmed via `\dt`.

### 6.1 Issue hit: a stale `claims` table schema from an early run

Symptom, discovered only once real claim data was written (Part 6, not Part 2 — the bug was silent until then): `ERROR: column "drop_id" is of type bigint but expression is of type uuid`.

Root cause: the very first time the app ever ran against this new project's Postgres instance, `Claim`'s fields hadn't yet been finalized to `UUID`, so Hibernate created `claims.id` and `claims.drop_id` as `bigint` (the JPA default). Once the entity was finalized to use `UUID`, `ddl-auto=update` attempted to `ALTER` those columns from `bigint` to `uuid` on every subsequent startup — but Postgres cannot auto-cast an integer to a UUID (they are unrelated representations, not just different formats). Hibernate logged these failures as `WARN`, not `ERROR`, so the app kept starting "successfully" for every test up through Part 5, since nothing had actually written to `claims` yet.

Fix: `DROP TABLE claims;` and let Hibernate recreate it fresh, with no incompatible legacy structure to fight against.

**Documented lesson:** `ddl-auto=update` will silently fail on structural changes it cannot figure out how to migrate, without stopping the application — a genuine limitation worth naming explicitly. A production system would use Flyway or Liquibase, which fail loudly and require explicit, reviewed migration steps instead of best-effort guessing.

---

## 7. Part 3 — API-Key Authentication

**Goal:** Jalsa authenticates server-to-server via `Authorization: Bearer <API_KEY>`; FlashGuard resolves this to an `Organization` without ever storing the raw key.

**`ApiKeyService`:**
- `generateRawKey()` — 32 bytes from `SecureRandom` (not `Math.random()`, which is predictable and unsafe for secrets), base64url-encoded, prefixed `fg_live_`.
- `hash(rawKey)` — SHA-256, hex-encoded.

**Why SHA-256, not BCrypt:** BCrypt is deliberately slow, to resist brute-forcing short, low-entropy human passwords. A FlashGuard API key is already a 256-bit random token — brute-forcing it is computationally infeasible regardless of hash speed. BCrypt would only add unnecessary latency to every authenticated request. SHA-256 is the standard choice for API keys (the same approach used by Stripe, GitHub, etc.).

**`POST /v1/organizations`** — creates an Organization, generates + hashes a key, persists only the hash, returns the **raw** key exactly once via `OrganizationResponse`. Excluded from `ApiKeyAuthFilter`, since a customer cannot present a key before they have one.

**`ApiKeyAuthFilter`** (a plain `OncePerRequestFilter`, see §5.1(C) for why no Spring Security dependency is used):
1. Excludes `/v1/organizations` and `/health` paths.
2. Requires `Authorization: Bearer <key>` on everything else.
3. Hashes the presented key, looks it up via `findByApiKeyHash`.
4. On no match / missing header → `401`.
5. On match → attaches the resolved `Organization` to the request (`request.setAttribute("organization", ...)`) for controllers to read.

---

## 8. Part 4 — Drop APIs and Multi-Tenancy

**`POST /v1/drops`** and **`GET /v1/drops/{id}`**, the first endpoints to actually exercise `ApiKeyAuthFilter`.

**Why a service layer here, unlike `OrganizationController`:** Drop creation touches two systems (Postgres + Redis, from Part 5 onward). Once an action coordinates more than one system, that logic belongs in a service, not directly in a controller.

### 8.1 The multi-tenancy gap — found and fixed

Initially, `GET /v1/drops/{id}` fetched by ID alone, with no check that the requesting Organization actually owned that Drop. Any valid API key — from any Organization — could read any other Organization's Drop data by guessing/knowing its UUID. This is a real authorization gap (authentication ≠ authorization): the filter correctly identifies *who* is calling, but nothing checked whether they were allowed to see *this specific resource*.

**Fix:** `DropService.getDrop(UUID dropId, Organization requestingOrg)` now checks `drop.getOrganization().getId().equals(requestingOrg.getId())`, throwing `DropNotFoundException` on mismatch — deliberately returning the same **`404`**, not `403`, whether the drop doesn't exist or belongs to someone else. Returning `403` would itself leak information (confirming the resource exists, just isn't accessible). This is standard practice for multi-tenant systems.

**Verified with a real two-tenant test:** registered a second Organization ("Sunburn Festival"), created a Drop under "Jalsa," then confirmed Sunburn's key got `404` on Jalsa's drop while Jalsa's own key got `200` on the same URL. This ownership check is reused unchanged by the Claim engine in Part 6, so cross-tenant claim isolation came "for free."

---

## 9. Part 5 — Redis Inventory Initialization

**`InventoryService`** centralizes all Redis interaction (kept deliberately separate from Postgres-facing `DropService`/`ClaimService`, so Redis key conventions and logic live in exactly one place).

- `inventoryKey(dropId)` → `"drop:" + dropId + ":inventory"` — a static, shared convention the claim engine (Part 6) reuses exactly, avoiding any risk of the two halves of the system disagreeing on key format.
- `initializeInventory(dropId, totalInventory)` — sets the Redis counter immediately after the Postgres `Drop` row is saved.

**Known gap flagged here, resolved in Part 8:** if the Postgres save succeeds but the Redis write fails, the Drop has no matching counter. See §12.1.

---

## 10. Part 6 — The Claim Engine

This is the core of the entire project.

### 10.1 Why not plain Java check-then-decrement

```java
int current = Integer.parseInt(redisTemplate.opsForValue().get(key));
if (current > 0) {
    redisTemplate.opsForValue().decrement(key);
    return SUCCESS;
}
```
Between the `GET` and the `decrement`, two concurrent requests can both read the same value, both pass the check, both decrement — overselling. This is the exact bug the whole project exists to prevent, and it is not hypothetical: under real concurrent load it happens routinely, not rarely.

### 10.2 Why Redis + Lua specifically

Redis executes commands single-threaded — it never runs two commands at the same instant. Wrapping "check, then decrement" in a single Lua script (`EVAL`) means Redis runs the *entire script* as one indivisible unit; there is no "middle" for a second request to interleave into. This is what atomicity means here: not speed, but indivisibility.

**Why not Redis's built-in `DECR` alone:** `DECR` has no concept of "don't go below zero" — it would happily decrement into negative numbers. The conditional logic (check *and* decrement as one unit) is exactly what a plain atomic command can't express; Lua scripting is the tool built for this.

**`scripts/claim.lua`:**
```lua
local current = tonumber(redis.call('GET', KEYS[1]))

if current == nil then
    return -1
end

if current > 0 then
    redis.call('DECR', KEYS[1])
    return 1
else
    return 0
end
```
- `-1` → the key doesn't exist (distinguishes "never initialized" from genuinely sold out — feeds directly into Part 8's self-heal logic).
- `1` → success, decrement performed.
- `0` → sold out, no change made.

### 10.3 Why the Postgres write happens after the Redis decision, not before

Redis is the fast, authoritative gatekeeper for "can this happen at all." Checking Postgres first would reintroduce the original race condition, just relocated to a slower database.

### 10.4 Why `SOLD_OUT` attempts are still persisted

Per the Part 2 decision — rejection-rate metrics require a record of rejected attempts, not just successes.

### 10.5 Verified

1. Created a Drop with `totalInventory: 3`.
2. Three sequential claims with distinct `customerReference`s → all `SUCCESS`.
3. A fourth claim → `SOLD_OUT`.
4. Redis inventory → `"0"`, not negative.
5. Postgres `claims` table → 4 rows, 3 `SUCCESS`, 1 `SOLD_OUT`.

---

## 11. Part 7 — Concurrency Load Test

**Tool: k6** (Grafana's load-testing tool), chosen over JMeter for two reasons: config-as-code (the test script lives in the repo as a real, readable file, not an opaque XML config a reviewer would need a separate GUI to inspect), and built-in percentile latency reporting out of the box.

**`loadtest/claim-test.js`:**
```javascript
import http from 'k6/http';
import { check } from 'k6';

const DROP_ID = '<drop id>';
const API_KEY = '<raw api key>';

export const options = {
    vus: 200,
    iterations: 10000,
};

export default function () {
    const res = http.post(
        `http://localhost:8080/v1/drops/${DROP_ID}/claims`,
        JSON.stringify({ customerReference: `user-${__VU}-${__ITER}` }),
        {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${API_KEY}`,
            },
        }
    );
    check(res, { 'status is 200': (r) => r.status === 200 });
}
```
`__VU`/`__ITER` guarantee a unique `customerReference` per request, simulating distinct customers rather than one customer retried.

**Setup:** a Drop with `totalInventory: 100`.

### 11.1 Results

**k6 summary (200 virtual users, 10,000 total requests):**
- `checks_succeeded`: 100.00% (10,000 / 10,000)
- `http_req_failed`: 0.00%
- Throughput: ~97.8 requests/sec
- Latency: avg 2.03s, median 1.69s, p90 3.65s, p95 4.42s, max 13.63s
- Total wall-clock time: 1m 42.2s

**Overselling proof (the actual point of the test):**
```sql
SELECT status, COUNT(*) FROM claims WHERE drop_id = '<id>' GROUP BY status;
```
```
 status   | count
----------+-------
 SUCCESS  |   100
 SOLD_OUT |  9900
```
```
GET drop:<id>:inventory  →  "0"
```

**Exactly 100 successes against exactly 100 configured slots, under 10,000 concurrent requests. Zero overselling. Redis inventory never went negative.** This is the direct, numeric proof of the project's core claim.

### 11.2 Honest latency analysis — a finding, not a failure

An average of 2+ seconds and a p95 of 4.4 seconds for a Redis-backed atomic operation is slow — Redis operations typically resolve in single-digit milliseconds, even under load. This was treated as a genuine finding to investigate and document rather than a number to bury.

**Likely root cause, based on request tracing:** every single claim request performs **three sequential Postgres round-trips** — (1) `ApiKeyAuthFilter`'s `findByApiKeyHash` lookup, (2) `DropService.getDrop`'s ownership-check read, (3) the final `Claim` row insert — and only **one** fast Redis call. With 200 concurrent virtual users, HikariCP's default connection pool size (10) is almost certainly the actual bottleneck: most requests are queuing for a free Postgres connection, not waiting on Redis. The "fast path" (Redis) is architecturally correct, but it's bookended by three sequential Postgres hits, so the overall request latency reflects Postgres contention, not Redis performance.

**This is documented deliberately as an identified target for future optimization** (e.g., tuning `spring.datasource.hikari.maximum-pool-size`, or making claim persistence asynchronous/batched) rather than fixed within this project's current scope — a real load test surfacing a real, explainable bottleneck is more credible than a suspiciously perfect one.

---

## 12. Part 8 — Failure Handling

**Goal:** address the two-database consistency gap explicitly, rather than assume Redis and Postgres always agree.

### 12.1 Scenario A: Drop creation — Postgres succeeds, Redis write fails

If Redis is unavailable at the exact moment `initializeInventory` runs, a Drop exists in Postgres with no matching Redis counter. Every claim against it would previously hit the Lua script's `-1` case, silently folded into `SOLD_OUT` — hiding a real infrastructure failure behind an ordinary business outcome.

**Fix — self-heal in `ClaimService`:**
```java
if (result == InventoryService.ClaimResult.INVENTORY_NOT_INITIALIZED) {
    inventoryService.initializeInventory(drop.getId(), drop.getTotalInventory());
    result = inventoryService.tryClaim(drop.getId());
}
```

**Documented, deliberate limitation:** this reset assumes the Redis key's absence means *no claims have ever succeeded* against this drop. It resets to `drop.getTotalInventory()` from Postgres, with no awareness of prior successful claims. This is only correct for a drop with zero claim history. A fully correct version would instead recompute `totalInventory - COUNT(successful claims so far)` via a Postgres query. This tradeoff was made explicitly and is called out rather than hidden.

**Verified — both the correct case and the known-flawed case, deliberately, to demonstrate the limitation is real and understood, not just theoretical:**
- **Correct case:** created a fresh Drop (`totalInventory: 5`), deleted its Redis key before any claims, made one claim → `SUCCESS`, Redis correctly read `"4"` afterward (5 reset, 1 decremented — accurate, since no prior claims existed).
- **Flawed case (as predicted):** deleted the Redis key on the Part 7 load-test Drop, which already had 100 real `SUCCESS` claims in Postgres. The self-heal reset the counter to `100` (its `totalInventory`) with no knowledge of the prior 100 claims, then decremented once to `99` — numerically wrong relative to reality (should have stayed sold out), exactly matching the documented limitation.

### 12.2 Scenario B: Claims — Redis succeeds, Postgres write fails

If Redis has already decided `SUCCESS` (a slot is now genuinely, irreversibly given out) but the subsequent `Claim` row insert to Postgres fails — e.g. connection pool exhaustion, per §11.2's finding — the inventory count is correct but the audit record is lost.

**Why this can't be "rolled back":** incrementing the Redis counter back to undo the claim risks a *different*, concurrently-arriving request claiming that same freed slot — reintroducing the exact overselling bug this project exists to prevent. The correct instinct is that the Postgres write must eventually succeed, not be abandoned.

**Fix — retry with linear backoff, then loud failure:**
```java
private Claim saveWithRetry(Drop drop, String customerReference, ClaimStatus status) {
    // ... up to 3 attempts, 100ms/200ms/300ms backoff between attempts
    // on final failure: log.error(...) and throw ClaimPersistenceException
}
```
Backoff is linear rather than immediate retry specifically because, per §11.2, the most likely real failure mode is connection-pool exhaustion — retrying instantly would add more pressure to an already-overloaded pool rather than give it time to recover.

**`ClaimController`** maps `ClaimPersistenceException` to a `500` with an explicit "please retry or contact support" message, distinct from the `404` used for ownership/not-found failures.

**Testing honesty:** this path (genuine mid-request Postgres outage) was not tested via an induced live outage, since reliably timing a Postgres failure mid-transaction requires chaos-engineering tooling outside this project's scope. It is verified by code review and reasoning about the failure mode, not empirical fault injection — stated plainly rather than implied to have been tested end-to-end.

---

## 13. Full List of Design Decisions and Known Limitations

For interview-readiness, every deliberate tradeoff made across the build, in one place:

1. UUIDs for all public-facing IDs, not sequential integers.
2. API keys stored only as SHA-256 hashes; raw key shown exactly once.
3. `SOLD_OUT` claims persisted, not discarded, to support rejection-rate metrics — at the cost of extra write volume on the hot path (a contributor to the Part 7 latency finding).
4. Drops default straight to `ACTIVE` on creation; no `DRAFT`→`ACTIVE` workflow exists yet.
5. `GET /v1/drops/{id}` requires authentication like writes do; whether reads should be public is an open question, left gated.
6. Ownership mismatches return `404`, not `403`, to avoid confirming a resource's existence to non-owners.
7. `ddl-auto=update` is used for development convenience; it silently fails on structural migrations it can't infer (demonstrated directly in §6.1) and would be replaced by Flyway/Liquibase in production.
8. Load testing surfaced Postgres connection-pool contention, not the Redis atomic operation, as the dominant latency factor — an identified, not yet implemented, optimization target.
9. Self-heal logic for a missing Redis inventory key resets to `totalInventory` with no awareness of prior claims — correct only when no claims yet exist for that drop; demonstrated as both correct and flawed depending on prior state.
10. Redis-succeeds/Postgres-fails on a claim write is handled via bounded retry with backoff, then a loud, explicit failure — the underlying outage itself was not empirically fault-injected, only reasoned through and code-reviewed.
# FlashGuard

**An overselling-proof claim engine for limited-slot drops.**

FlashGuard is a pluggable backend infrastructure service that guarantees a limited inventory — VIP passes, flash-sale slots, event tickets — can never be oversold, even when thousands of requests hit it in the same instant. Built with Spring Boot, PostgreSQL, and Redis, and load-tested to prove it.

> **The problem it solves:** 100 VIP passes go live. 10,000 people click "Claim" at the same second. Without atomic concurrency control, a naive system will sell 105, 110, sometimes more — inventory checks and decrements racing against each other. FlashGuard makes that structurally impossible.

---

## The core guarantee

> No matter how many concurrent claim requests arrive, FlashGuard will never allow more successful claims than the configured inventory.

This isn't a design intention — it's a tested, measured result. See [Results](#results) below.

---

## Why this exists

FlashGuard is not a ticketing platform. It's infrastructure a real ticketing/e-commerce backend (referred to throughout the project as "Jalsa," a hypothetical customer) would integrate for the one moment that actually matters: the high-concurrency instant where a limited number of slots get handed out. Jalsa still owns its users, payments, orders, and ticket delivery — FlashGuard owns exactly one thing, correctly: **who gets a slot, and who doesn't, under real concurrent load.**

| FlashGuard owns | The integrating platform owns |
|---|---|
| Organizations & API credentials | End customers |
| Drops (limited-slot inventory) | Website / frontend |
| Claim concurrency & correctness | Payments, orders |
| Claim audit records | Tickets, notifications |
| Overselling prevention | Everything else |

---

## Architecture

```
Users (thousands, concurrent)
        │
        ▼
Integrating platform's backend
        │  Authorization: Bearer <API key>
        ▼
FlashGuard (Spring Boot)
        │
        ├── API-key auth + multi-tenant ownership checks
        ▼
   Claim Engine
        │
        ▼
 ┌──────────────┐        ┌──────────────┐
 │    Redis     │        │  PostgreSQL  │
 │ Hot inventory│        │ Durable data:│
 │ Atomic claim │        │ Orgs, Drops, │
 │ (Lua script) │        │ Claims       │
 └──────────────┘        └──────────────┘
        │
        ▼
  SUCCESS / SOLD_OUT, returned synchronously
```

**Redis** is the fast, atomic gatekeeper — a Lua script makes "check inventory, then decrement" a single indivisible operation, so no two concurrent requests can ever race each other.
**PostgreSQL** is the durable system of record — every claim attempt, successful or not, is persisted for audit and rejection-rate reporting.

Multi-tenant by design: many organizations can run drops on one FlashGuard deployment, each fully isolated — verified with real cross-tenant access tests, not just assumed.

---

## Results

Load tested with **k6**: 200 concurrent virtual users, 10,000 total claim requests, against a drop configured with exactly **100** slots.

| Metric | Result |
|---|---|
| Successful claims | **100** — exactly matching configured inventory |
| Rejected claims (`SOLD_OUT`) | 9,900 |
| Overselling | **0** |
| Final Redis inventory | `0` (never negative) |
| HTTP failures | 0 / 10,000 |
| Throughput | ~97.8 req/s |
| Latency (p95) | 4.42s* |

*\*Load testing surfaced Postgres connection-pool contention — not the Redis atomic operation — as the dominant latency factor, since each claim performs three sequential Postgres round-trips (API-key auth, ownership check, claim persistence) around one fast Redis call. Documented as an identified optimization target (connection pool tuning, async claim persistence) rather than fixed within the current scope — see full build documentation for the detailed trace.*

The number that actually matters: **exactly 100 successes out of 10,000 concurrent attempts against 100 slots.** Not 101. Not 99.

---

## What's implemented

- **Multi-tenant Organizations**, each with its own API key (SHA-256 hashed at rest — the raw key is shown exactly once, at creation)
- **Drop management** — create and fetch limited-inventory "drops," with ownership enforced (an organization can never see or claim against another organization's drop — returns `404`, not `403`, to avoid confirming the resource even exists)
- **Atomic claim engine** — a Redis Lua script performs the check-and-decrement as one indivisible operation
- **Full audit trail** — every claim attempt, successful or rejected, is persisted to Postgres
- **Failure handling** — self-healing inventory recovery if a Redis counter is ever lost, and retry-with-backoff if a successful claim's database write fails
- **Load-tested concurrency correctness** — not claimed, measured

## Known limitations (documented, not hidden)

- Self-healing a missing Redis inventory counter resets to the drop's original total, with no awareness of claims that already happened — correct only for a drop with no prior claim history. A production version would recompute from the claims table instead.
- The Redis-succeeds/Postgres-fails failure path is handled via bounded retry and explicit alerting, but was verified by code review and reasoning rather than an induced live outage (accurately simulating that requires chaos-engineering tooling outside this project's scope).
- Postgres connection-pool sizing under high concurrency is an identified, not yet implemented, performance improvement.

---

## Tech stack

**Backend:** Java, Spring Boot, Spring Data JPA
**Data:** PostgreSQL, Redis (Lua scripting)
**Infra:** Docker Compose
**Load testing:** k6

---

## Running locally

```bash
# 1. Start Postgres + Redis
docker compose up -d

# 2. Run the app (set JVM timezone flag to avoid a known Windows/Postgres timezone alias issue)
./mvnw spring-boot:run -Duser.timezone=Asia/Kolkata

# 3. Register an organization to get an API key
curl -X POST http://localhost:8080/v1/organizations \
  -H "Content-Type: application/json" \
  -d '{"name": "Jalsa"}'

# 4. Create a drop
curl -X POST http://localhost:8080/v1/drops \
  -H "Authorization: Bearer <your API key>" \
  -H "Content-Type: application/json" \
  -d '{"name": "VIP Pass", "totalInventory": 100}'

# 5. Claim a slot
curl -X POST http://localhost:8080/v1/drops/<dropId>/claims \
  -H "Authorization: Bearer <your API key>" \
  -H "Content-Type: application/json" \
  -d '{"customerReference": "user-1"}'
```

Full build documentation — including every design decision, the reasoning behind it, and every test performed — is in [`FlashGuard-Build-Documentation.md`](./FlashGuard-Build-Documentation.md).
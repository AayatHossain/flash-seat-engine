# 0001: Postgres decides who owns a seat

- Status: accepted
- Date: 2026-11-26

## Context

During a flash sale thousands of buyers try to hold the same few seats at the same time. The one thing the system must never do is give a seat to two buyers. Every service replica sees the same requests, so the decision cannot live in application memory.

## Decision

- Postgres is the source of truth for seat ownership.
- A hold claims its seats with a single conditional `UPDATE ... WHERE status = 'AVAILABLE'` inside one transaction. If fewer rows change than the buyer asked for, the transaction rolls back and the buyer gets nothing (all or nothing).
- Seats are locked in `seat_id` order (`ORDER BY seat_id FOR UPDATE`), so buyers who ask for overlapping seats in different orders wait for each other instead of deadlocking.
- Confirm and release are also conditional updates and are safe to repeat.
- The schema backs this up: a `CHECK` constraint ties `seats.status` to `seats.hold_id`, so an impossible seat state cannot be stored even by buggy code.
- Hold expiry uses database time (`now()`), not the clock of whichever replica handles the request.

## Consequences

- Correctness is easy to reason about and is proven by a test where 200 concurrent buyers race for one seat and exactly one wins.
- Every hold attempt reaches the database, and a very popular seat becomes a hot row. A Redis filter in front of Postgres (planned) will reject obvious losers early, but Postgres stays the final authority. Redis alone was rejected because its replication is asynchronous and a failover could lose claims and oversell.

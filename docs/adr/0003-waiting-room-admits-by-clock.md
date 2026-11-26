# 0003: The waiting room admits buyers by the clock, not by a background job

- Status: accepted
- Date: 2026-11-27

## Context

When a popular sale opens, far more buyers arrive than checkout can serve. Letting everyone hit the seat-hold endpoint at once would overload the database and make the experience a lottery of timeouts. We need a queue that hands out fair positions and lets buyers through at a rate checkout can sustain, while running on several replicas.

## Decision

- Joining is a single atomic Lua script in Redis: if the buyer already has a position it is returned, otherwise a counter is incremented and stored for them. Positions follow arrival order, are unique and have no gaps, and a buyer who clicks "join" many times keeps one position.
- Who is admitted is calculated, not stored: `admitted positions = seconds since the sale opened × admit-per-second`. Every replica computes the same answer from the clock, so there is no background job to run and no leader to elect.
- An admitted buyer receives a signed admission token (see the hold API); the hold endpoint refuses requests without one. Checking the token needs no database or Redis call.
- The buyer's identity is read from a `User-Id` header that the API gateway sets after checking the login token.
- Sale opening times are cached in memory because status is polled constantly and opening times do not change.

## Consequences

- Load on checkout is capped by configuration (`flashseat.waiting-room.admit-per-second`), whatever the size of the crowd. Tested with 2,000 simultaneous joins: unique positions 1 to 2,000, no gaps.
- The rate is fixed per deployment. A feedback loop that slows admission when checkout latency rises is a natural next step; it needs the rate history stored in Redis instead of a single constant.
- There is no pre-sale shuffle yet. Buyers who join before the sale opens are queued in arrival order, which rewards bots that join earliest. Randomising positions for everyone who joined before opening is planned.
- Admitted buyers can ask for a fresh token while it is valid; a fixed admission window per position is planned.

# 0002: Checkout is an orchestrated saga driven by provider webhooks

- Status: accepted
- Date: 2026-11-26

## Context

Paying for an order touches three systems that cannot share a transaction: the order database, the inventory service (which owns the seats), and the payment provider. Any of them can be slow or fail at any moment, and the provider tells us about payments through webhooks that may arrive late, twice, in parallel or out of order.

## Decision

- The order service orchestrates checkout (`CheckoutSaga`). One class holds the whole flow, which makes it easy to read and test. Choreography through events was considered, but with only two participants it would spread one flow across several services for no benefit.
- On "payment succeeded": confirm the seats with inventory, then move the order to `PAID` and record `OrderPaid`.
- Compensation: if inventory says the hold already expired or was released, the order moves to `EXPIRED` (`SeatsLost`) and the payment is refunded (`OrderRefunded`). We refund rather than try to grab other seats; a buyer should never end up with seats they did not choose.
- Every step can be repeated safely:
  - inventory confirm is idempotent;
  - provider calls carry idempotency keys (`order-<id>`, `refund-<id>`);
  - order status changes are conditional updates. When parallel deliveries race, exactly one changes the status and writes the event.
- Failures are not caught and hidden. The webhook answers with an error, the provider delivers the event again later, and the saga continues from where it stopped. A provider event is recorded as processed only after its work is done.
- A failed card attempt (`payment_intent.payment_failed`) does not cancel the order. With Stripe the buyer can retry another card on the same payment, so the order stays `PAYMENT_PENDING` and a `PaymentFailed` event is recorded. If nobody pays, the seat hold times out and the seats go back on sale.
- Webhooks must carry a valid HMAC signature over the timestamp and body. Signatures older than five minutes are refused, so a captured webhook cannot be replayed.

## Consequences

- No double charges, no double refunds, and no paid order without seats. This is covered by tests for duplicate, parallel and late webhooks, and for an inventory or provider outage in the middle of the saga.
- Completing checkout depends on the provider retrying webhooks. A periodic reconciliation job that asks the provider about payments stuck in `PAYMENT_PENDING` would remove that dependency; it is noted as future work.
- Orders whose hold expires without any payment still need to be moved to `EXPIRED`. That needs inventory to publish hold-expiry events, which is planned.

# Contract: facts and contract additions

## `payments.merchants` (spec 001 FR-011; contract `PaymentsMerchants`, defined)

Produced by the `MerchantFacts` consumer from the sub-brand's key events; record key = the key id.

| Event | Family | Fields |
|---|---|---|
| `KeyGenerated` | `merchant_key_issued` | `key_id`, `sub_brand`, `digest`, `occurred_at` |
| `KeyDisabled` | `merchant_key_deactivated` | `key_id`, `sub_brand`, `occurred_at` |

Never a raw key (FR-005). `ingest` builds its key cache from this topic and refreshes from
`GET /v1/cashier/keys`.

## `platform.audit` (spec 001; contract `AuditRecord`, defined) — research R13, verify

Produced by `AuditFacts` from the events of `Holding`, `Company`, `SubBrand`, `Terminal`,
`RoutingTree`, `Operator`, `Payment`. `service = merchant`; `brand` = the sub-brand's brand, or
`payments` for the hierarchy and operators; `action` = the event's name in the glossary's words
(`holding created`, `merchant key generated`, `routing tree deployed`, `payment captured`, …);
`actorKind` and the actor from the command (an operator's subject, `ingest`, `psp-gateway`, the
workflow); `changes[]` for status and scope changes. Needs the topic on the shared manifest.

## The brand's `payments.payments`

Not produced here. The brand's wallet produces `payment.initiated`, `payment.captured`,
`payment.failed`, `payment.expired`, `payment.abandoned` from the two calls in
[wallet.md](wallet.md) (spec 002 FR-022a; contract `PaymentsPayments`, defined, every family naming
the provider).

## Contract additions (additive, this feature)

| Addition | Where | Why |
|---|---|---|
| `Routes.paymentsOperator(authenticate)` | `contract.access.Routes` | the staff issuer, a role in the privilege table, scope from `eitheror_scope_level` and `eitheror_scope_id` (research R10, decision 0050); `Refusal.ScopeViolation` |
| `Amounts` | `contract.money` | decimal string ↔ `MinorUnits` by currency exponent, hoisted from game-provider (R11) |
| `secret` on the gateway catalogue's required property | this service's `Catalogue` (not the contract) | R9 |

`just schemas` leaves no diff: neither addition touches a fact.

# Facts in

Research R3 to R5. The facts are spec 012's fourteen kinds, as the contract's
`AffiliatesAttribution` families (`contract/schemas/affiliates.attribution.v1.json`); this file says
only how they reach the programme and what it answers.

## A brand here: its topic

For each slug in `PROGRAMME_BRANDS`, a consumer `attribution-<slug>` of
`ChangeSource.fromTopic("<slug>", "affiliates.attribution", AttributionFact codec)`, from `Earliest`.
The grant is `core`'s (`modules/core/module.json:552-558`). A message is handed to `Intake` with the
channel `topic:<slug>`; the offset is committed when `Intake` returns, so a failure is redelivered.

## A brand elsewhere: its webhook

`POST /v1/programme/inbound/{brand}`, the body one fact, the headers spec 012 defines
(contracts/protocol.md *A webhook post* in spec 012).

| Check, in order | Answer when it fails |
|---|---|
| `{brand}` is a `BrandProgramme` with origin `elsewhere` or `hosted`, not retired | `404 brand_unknown` |
| body at most 256 KiB | `413` |
| `X-Attribution-Protocol` is `1` | `400 protocol` |
| `X-Attribution-Signature` is `sha256=<hex>` of HMAC-SHA256(body, `brand/<slug>/webhook-secret`), compared in constant time | `401 signature` |
| the body parses as a fact | `400 fact` |
| `X-Attribution-Fact-Id` equals the body's `factId` | `400 fact_id` |
| the body's `brand` is `{brand}` | `202`, recorded as mismatched, nothing else written |

Then `Intake` with the channel `webhook:<slug>`; `200 {"outcome":"new"|"duplicate"}` when `FactInbox`
holds it, `503` when it does not (the brand retries).

## `Intake`

1. `FactInbox.claim(fact, channel)`: `duplicate` → `BrandProgramme.countDuplicate`; stop.
2. `click` → `ClickRecord.record`; stop.
3. Read `BrandProgramme` (GGR source) and, for `first-deposit`, the plan's minimum through the player's
   membership; `PlayerAttribution.apply(fact, context)`.
4. `FactInbox.outcome(answer)`.

`ToLedger` takes it from there, over the player's events (data-model.md).

## The documents

Served as spec 012's contracts/protocol.md *The documents a network publishes* defines:

| Document | Route | `cacheSeconds` / `refreshSeconds` |
|---|---|---|
| Key document | `GET /v1/programme/documents/keys` | 3600; no `transition` member |
| Revocation list | `GET /v1/programme/documents/revocations` | refresh 300 |
| Legacy tag document | `GET /v1/programme/documents/legacy-tags` | 3600; `404` after the transition |
| Code document | `GET /v1/programme/documents/codes/{brand}` | 300; `version` bumps on every change to a live code at the brand |

# Quickstart: proving the feature

## 1. The behaviours, with no browser (US1 to US6, US8)

```bash
npm ci
just player-sdk-test          # typecheck, unit, the server's tests
just player-sdk-features      # features/player-site/*.feature except reference-site, over doubles
```

Expect every scenario green and no test waiting on a real timer (the whole run under a minute).

## 2. The amended services (US1, US2, US3, US5)

```bash
just players-test             # deposit-start.feature, the two last-bet scenarios of sessions.feature, the parity suite
just wallet-test              # the outline of reads.feature: major units by the currency's decimals
just service game-provider test  # the catalogue read by a visitor (spec 003 FR-027); the parity suite
```

## 3. The reference site on this machine (US7)

```bash
just player-site-run          # ankka local web -- npm run dev, services found as the local console finds them
```

Open the address it prints: the lobby in French; switch to English; register; follow the
verification link from the mail catcher; sign in; deposit 50,00 € through the payment provider
stub; pick a game, then a second, and switch between them; set a reality check through `responsible-gambling`'s route and see it fire;
open the chat. In the browser's tools: no cookie readable by script but `eo_lang`; no request to
any origin but the site's; no token in any response.

## 4. A second appearance (US4, SC-008)

```bash
just player-site-features     # reference-site.feature in a browser against the built site and the doubles
npm test -w services/player-site  # tests/second-appearance: composing's two scenarios; the words; the import rule
```

## 5. On `dev-kind` (US7, FR-036)

```bash
just modules render guyanabet --modules casino,engagement,support,player-site
just modules apply guyanabet --modules casino,engagement,support,player-site --installation dev-kind
just player-site-it           # one contract test per client function; then the end-to-end scenario
```

## 6. What must not be possible (SC-003, SC-004)

`just player-sdk-test` includes `tests/unit/noWayRound.test.ts`: for every step of every behaviour
and every action name, an action the step does not allow sends nothing; and from each refused
step no sequence of allowed actions reaches a cashier submit, a game address or a turn without a
new answer from the brand. `tests/server/secrecy.test.ts` searches every response, log line and
trace of a signed-in run for the issued values.

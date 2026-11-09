# Routes: reporting

One endpoint, `ReportingEndpoint`, prefix `/v1/casino/brands/<slug>/reporting` (research R13). Every
path below is under it. The backoffice's base URL for these is the prefix; the paths after it are the
original's (`C/ReportingRoutes.scala`, `C/DashboardRoutes.scala`), so nothing else in the backoffice
changes (spec 001 FR-033, SC-007).

Common to every route:

- Money is the original's 2-dp string unless the name ends in `MinorUnits`; counts are numbers.
- `currency` (optional) picks a currency the brand holds figures in; another is `400
  unknown_currency` naming the ones it holds (FR-003). Every answer carries `currency` and `currencies`.
- `from`, `to` are `YYYY-MM-DD` reporting days in the brand's zone, inclusive. A period before the
  earliest complete day is `409 incomplete_period {completeFrom}` (FR-004).
- An answer over the synchronous limit, or any `format=csv` export over it, is `202 {reference,
  status}` (FR-022); `GET /documents/{reference}` serves it.
- A `from` or `to` that is not a `YYYY-MM-DD` date is `400 invalid_date {field}` (added 2026-11-08); the dispute
  report keeps the original's own texts for its dates.
- `format=csv` answers an attachment `<report>-report-<reference>.csv` (`ggr-report-…`, `players-report-…`,
  `transactions-report-…`), CRLF lines, a cell quoted only when it holds a comma, a quote or a line break (the
  original's quoting), a header row of the JSON answer's field names, money as the JSON's 2-dp strings. The revenue
  CSV's columns are `date, bets, wins, ggr, depositPoolGgr, bonusCosts, chargebacks, ngr, deposits, withdrawals,
  netCashFlow, currency, ngrBasis`; its first row after the header is the summary, dated `<from>/<to>`, then one
  row per day (added 2026-11-08). Every report CSV with a summary puts it first: the bonus and provider CSVs label
  it `all` in the first column, then one row per offer or provider; the bonus CSV's columns are the offers' fields
  and `activeCount`, filled on the summary row only.
- Refusals are `{"error": <code or the original's text>}`.

## Admission

| Paths | ACL |
|---|---|
| `/reports/*` | `operatorHolding(slug, brand_admin, resource_admin, platform_admin)`; `404 not_found` when `reportsTab` is off |
| `/dashboard/*`, `/players/{playerId}/dispute-report` | `operator(slug)` (every operator of the brand) |
| `/dashboard/predictions` | `operator(slug)`; `404 not_found` unless `CASINO_MODULES` names `predictions` (added 2026-11-09) |
| `/compliance/*` | the three and `brand_compliance` |
| `/day-end/*` | the three and `brand_finance` |
| `/predictions/summary` | the three; `404 not_found` unless `CASINO_MODULES` names `predictions` |
| `/predictions/traders` | the three and `brand_compliance`, as `/compliance/*` (decided 2026-11-09); the same `404` |
| `/predictions/markets` | the three and `markets`; the same `404` |
| `/documents/{reference}` | the admission of the document's kind (a dispute report every operator, a report export the three, a day-end export the three and `brand_finance`, a compliance export the three and `brand_compliance`); the traders report's export as the traders report (decided 2026-11-09); `403 forbidden` otherwise; an export is `404 not_found` while its report is (a Reports tab report's while the tab is hidden, a prediction report's while the module is off, whatever the tab) |
| `GET`/`PUT /settings` | `brand_admin`, `platform_admin` |
| `GET /status` | `operator(slug)` |
| `POST /erasure/players/{playerId}` | `Routes.playersOnly` |

## The original's routes

| Route | Query | Answer | Change |
|---|---|---|---|
| `GET /reports/ggr` | `from`, `to` (month to date), `currency`, `format` | `{from, to, currency, currencies, summary:{totalBets, totalWins, ggr, depositPoolGgr, bonusCosts, chargebacks, ngr, ngrBasis:"before programme fees", totalDeposits, totalWithdrawals, netCashFlow}, dailyBreakdown:[{date, bets, wins, ggr, depositPoolGgr, bonusCosts, ngr}]}` | every player; the brand's zone; `depositPoolGgr`, `chargebacks`, `ngrBasis`, `currencies` added; `format=csv` new (summary row then daily rows) |
| `GET /reports/players` | `from`, `to`, `country`, `status`, `format` | `{from, to, totalCount, players:[{playerId, email, country, registrationDate, status, kycStatus, lifetimeDeposits, lifetimeWagered, lifetimeGgr, lastLoginAt, isTestAccount, currency}]}` | `isTestAccount` from the fact; email `[erased]` after erasure |
| `GET /reports/bonus` | `from`, `to`, `offerId` (`all`), `format` | `{from, to, summary:{totalGrantsCount, totalGrantedValue, convertedCount, conversionRatePct, forfeitedCount, cancelledCount, activeCount, currency}, offers:[{offerId, offerName, offerType, grantsCount, totalGrantedValue, convertedCount, convertedValue, forfeitedCount, cancelledCount, conversionRatePct, currency}]}` | `conversionRatePct` the truncated whole percent, as the original |
| `GET /reports/transactions` | `from`, `to`, `type` (`all`), `playerEmail`, `page` (0), `pageSize` (100, ≤ 100) | `{from, to, page, pageSize, totalCount, totalPages, transactions:[{transactionId, timestamp, playerId, truncatedEmail, transactionType, amount, currency, reference}]}` | every transaction; `truncatedEmail` 25 characters and `…` |
| `GET /reports/transactions/export` | as above, `format` (`json`) | as above, every row; `format=csv` new | no 10,000 cap; over the limit a job |
| `GET /reports/psp` | `from`, `to`, `currency`, `format` | `{from, to, currency, summary:{depositAttempts, completedDeposits, depositValue, depositSuccessRatePct, withdrawalRequests, completedWithdrawals, avgWithdrawalHours, currency}, pspBreakdown:[{pspName, depositAttempts, completedDeposits, depositSuccessRatePct, depositValue, withdrawalCount, withdrawalRequests, avgWithdrawalHours, belowThreshold}]}` | one row per provider; rates `null` with no attempts, `avgWithdrawalHours` `null` with no completed withdrawal (decided 2026-11-09); `belowThreshold` under 95 |
| `GET /dashboard/kpis` | `currency` | `{activeSessions, activeGames, playersOnline, ggrToday, ngrToday, depositsToday, withdrawalsToday, registrationsToday}` in the original's shapes (`{value, yesterday, trend[, currency]}`, `{amount, count, yesterdayAmount, trend, currency}`) | yesterday is yesterday's same hour; every player; no FX |
| `GET /dashboard/trend` | `range` (`today`, `7d`, `30d`, `90d`; other → `7d`), `currency` | `{range, currency, dataPoints:[{label, ggr, ngr}]}`; labels `HH:00`, `YYYY-MM-DD`, `YYYY-Www` | the brand's zone |
| `GET /dashboard/kpi-history` | `currency` | `{currency, days:[{label, date, ggr, ngr, depositAmount, depositCount, withdrawalAmount, withdrawalCount, registrations}]}`, 7 days, `Today` then short weekdays | every player |
| `GET /dashboard/geo` | — | `{countries:[{countryCode, playerCount}]}` by count | — |
| `GET /dashboard/rankings` | `range`, `currency` | `{range, games:[{gameId, gameName, provider, sessionCount}], players:[{playerId, truncatedEmail, countryCode, ggrAmount, totalWagered, currency}]}`, 10 each | game names from rounds; players every one |
| `GET /dashboard/activity` | — | `{events:[{eventId, eventType, playerId, truncatedEmail, amountMinorUnits:null, currency:null, occurredAt}]}`, today, newest first, 50 | — |
| `GET /dashboard/bonus-summary` | `currency` | `{activeBonusCount, totalLiability, bonusCostToday, totalLiabilityMinorUnits, bonusCostTodayMinorUnits, avgWageringCompletionPct, currency}` | both field-name sets; figures, not zeros |
| `GET /dashboard/payment-health` | `currency` | `{depositSuccessRatePct, avgWithdrawalHours, currency, pspBreakdown:[{pspName, depositVolume, depositCount, successRatePct}]}` | figures; `null` with no attempts, `avgWithdrawalHours` `null` with no completed withdrawal (decided 2026-11-09); `depositVolume` a 2-dp string where the original's was a number (decided 2026-11-08) |
| `GET /players/{playerId}/dispute-report` | `sessionId` xor (`fromDate`, `toDate`), `format` (`html`, `csv`, `pdf`) | `200` attachment `dispute-report-{reference}.{ext}`; HTML and CSV in `ReportService`'s layout | `pdf` new; over the limit `202`; the game's name and type from rounds; status the outcome |

Dispute report refusals, the original's texts and order: `400 "provide either sessionId or
fromDate+toDate, not both"`, `400 "provide either sessionId or both fromDate and toDate"`, `400 "date
range must not exceed 365 days"`, `400 "fromDate must be before toDate"`, `400 "invalid date format; use
ISO 8601 (YYYY-MM-DD)"`, `404 player_not_found`, `404 session_not_found`.

Not served, as before: the original's failure bodies (`200` with empty arrays, `503`) are not imitated; a
view always answers.

## New routes

| Route | Answer |
|---|---|
| `GET /dashboard/predictions` | `currency`: `{currency, currencies, contractsTraded, notionalVolume, trades, traders, takerFees}`, today in the brand's zone, each in the KPIs' shape: counts `{value, yesterday, trend}`, money `{value, yesterday, trend, currency}` (2-dp strings); yesterday is yesterday to the same hour, `trend` the KPIs' arrow; `traders` the distinct players who traded (yesterday's, those whose first trade of the day was by that hour). `404 not_found` unless the brand runs the predictions module; no other dashboard route mentions predictions, and `/dashboard/kpis` keeps its shape (added 2026-11-09) |
| `GET /day-end/{day}` | `{day, zone, closedAt, reference, currencies:{<c>:{deposits:[{provider, count, amount}], withdrawals:[{gateway, count, amount}], games:[{provider, gameId, gameName, providerGgr, walletGgr}], walletGgr, depositPoolGgr, bonusCost, chargebacks, liability, pendingHolds, predictions?:{markets:[{marketId, contractsTraded, notionalVolume, trades, takerFees, resolutionPayments, remainderKept, feeCredits}], takerFees, feeCredits, feeDifferences:[{tradeId, marketId, takerFee, feeCredit, status}]}}}, differences:[{roundId, provider, providerStake, providerWin, walletStake, walletWin, status}], corrections:[{arrivedAt, day, figures:{currency, bets, wins, ggr, depositPoolGgr, bonusCost, chargebacks, ngr, deposits, depositCount, withdrawals, withdrawalCount, registrations}}], openingCorrections:[{arrivedAt, day, figures}]}` (as built, 2026-11-09). Currencies by name, every list sorted: deposits per payment provider (`count` the completed deposits, a provider with attempts and none completed listed with 0), withdrawals per gateway (completed), games per provider and game (a round only the wallet holds under `unknown`), `walletStake` net of rollbacks; a difference's `status` `differs`, `provider_only` or `wallet_only`, its `provider` `""` for a round only the wallet holds; a fee difference's `differs`, `fee_only` or `credit_only` (its `marketId` `""`). `predictions` only when the brand runs the module. `reference` is the same on every read of the day; a closed day reads byte for byte the same, a late fact adding only a `corrections` entry (whose `day` is this one) and an `openingCorrections` entry of the next day (whose `day` is the corrected one). `409 day_open` before the close; `400 invalid_date {field:"day"}` for a day that is not a date |
| `GET /day-end/{day}/export` | the day-end as a `day-end-export` document, the attachment `day-end-report-<reference>.csv`: long format, header `section, currency, item, field, value`, one row per figure. Sections in order: `day-end` (no currency or item; fields `reference` the document's own, `dayEnd` the day-end's, `day`, `zone`, `closedAt`), then per currency `deposits` (item the provider; `count`, `amount`), `withdrawals` (item the gateway; `count`, `amount`), `games` (item `<provider>/<gameId>`; `gameName`, `providerGgr`, `walletGgr`), `totals` (no item; `walletGgr`, `depositPoolGgr`, `bonusCost`, `chargebacks`, `liability`, `pendingHolds`) and, with the module, `predictions` (item the market; its seven figures) and `fee-differences` (item the trade; `marketId`, `takerFee`, `feeCredit`, `status`); then `differences` (no currency, item the round; the six fields), `corrections` and `opening-corrections` (currency the correction's, item its number from 1; `arrivedAt`, `day` and the figures) (as built, 2026-11-09) |
| `GET /compliance/rg-cases` | `from`, `to`, `format`: `{rows:[{playerId, type, openedAt, closedAt, outcome, reason}]}`: one row per RG case (opened and closed in one row), per limit applied (`type` the limit's, `outcome` `applied`, `reason` its `caused_by`) and per self-exclusion (`type` `self_exclusion`); a row is in the period when it opened or closed in it; `openedAt`/`closedAt` `null` when not known. `format=csv` the same columns as a `compliance-export` document, `rg-cases-report-<reference>.csv` (as built, 2026-11-09) |
| `GET /compliance/kyc-decisions` | `from`, `to`, `format`: `{rows:[{playerId, category, submittedAt, decidedAt, outcome, reason}]}`: one row per document decision (`category` the document's) and per status decision (`category` `status`; a pending status is no decision; `submittedAt` the player's last submission); a row is in the period when it was decided in it. `format=csv` the same columns as a `compliance-export` document, `kyc-decisions-report-<reference>.csv` (as built, 2026-11-09) |
| `GET /predictions/summary` | `from`, `to`, `currency`: `{contractsTraded, notionalVolume, trades, traders, takerFees, resolutionPayments, remaindersKept, marketsOpened, marketsResolved, marketsVoided, marketsCorrected}` |
| `GET /predictions/markets` | `status`, `category`, `from`, `to`, `format`: `{markets:[{marketId, question, category, currency, status, openedAt, closedAt, resolvedAt, resolution, firstResolution, corrected, contractsTraded, notionalVolume, trades, traders, openInterest, takerFees, resolutionPayments, remainderKept}]}`; `format=csv` a `report-export` document, `predictions-markets-report-<reference>.csv`, the same columns |
| `GET /predictions/traders` | `from`, `to`, `currency`, `format`: `{traders:[{playerId, email, contractsTraded, notionalVolume, takerFees, positionsPaid, netResult}]}`; `format=csv` a `report-export` document, `predictions-traders-report-<reference>.csv`, the same columns |
| `GET /documents/{reference}` | the attachment when ready; `202 {reference, status, progress}` while generating, `progress` a whole percent; `410 document_expired` after; `500 document_failed` when its job failed (ask again) |
| `GET /documents` | `kind`, `from`, `to` (the month to date): `{documents:[{reference, kind, format, parameters, from, to, askedBy, generatedAt, readyAt, expiresAt, status, endedBy, rows}]}`, newest first, only the kinds the caller is admitted to |
| `GET`/`PUT /settings` | `ReportingSettings` in camelCase; `PUT` changes only the fields it names, and `null` clears an optional one (`defaultCurrency`); `400 invalid_setting` naming the field and its range |
| `GET /status` | `{generation, views:[{name, completeFrom, rebuilding}]}` |
| `POST /erasure/players/{playerId}` | `{service:"reporting", subject, documents}` |

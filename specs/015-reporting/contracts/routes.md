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
  row per day (added 2026-11-08).
- Refusals are `{"error": <code or the original's text>}`.

## Admission

| Paths | ACL |
|---|---|
| `/reports/*` | `operatorHolding(slug, brand_admin, resource_admin, platform_admin)`; `404 not_found` when `reportsTab` is off |
| `/dashboard/*`, `/players/{playerId}/dispute-report` | `operator(slug)` (every operator of the brand) |
| `/compliance/*` | the three and `brand_compliance` |
| `/day-end/*` | the three and `brand_finance` |
| `/predictions/summary`, `/predictions/traders` | the three; `404 not_found` unless `CASINO_MODULES` names `predictions` |
| `/predictions/markets` | the three and `markets`; the same `404` |
| `/documents/{reference}` | the admission of the document's kind |
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
| `GET /reports/psp` | `from`, `to`, `currency`, `format` | `{from, to, currency, summary:{depositAttempts, completedDeposits, depositValue, depositSuccessRatePct, withdrawalRequests, completedWithdrawals, avgWithdrawalHours, currency}, pspBreakdown:[{pspName, depositAttempts, completedDeposits, depositSuccessRatePct, depositValue, withdrawalCount, withdrawalRequests, avgWithdrawalHours, belowThreshold}]}` | one row per provider; rates `null` with no attempts; `belowThreshold` under 95 |
| `GET /dashboard/kpis` | `currency` | `{activeSessions, activeGames, playersOnline, ggrToday, ngrToday, depositsToday, withdrawalsToday, registrationsToday}` in the original's shapes (`{value, yesterday, trend[, currency]}`, `{amount, count, yesterdayAmount, trend, currency}`) | yesterday is yesterday's same hour; every player; no FX |
| `GET /dashboard/trend` | `range` (`today`, `7d`, `30d`, `90d`; other → `7d`), `currency` | `{range, currency, dataPoints:[{label, ggr, ngr}]}`; labels `HH:00`, `YYYY-MM-DD`, `YYYY-Www` | the brand's zone |
| `GET /dashboard/kpi-history` | `currency` | `{currency, days:[{label, date, ggr, ngr, depositAmount, depositCount, withdrawalAmount, withdrawalCount, registrations}]}`, 7 days, `Today` then short weekdays | every player |
| `GET /dashboard/geo` | — | `{countries:[{countryCode, playerCount}]}` by count | — |
| `GET /dashboard/rankings` | `range`, `currency` | `{range, games:[{gameId, gameName, provider, sessionCount}], players:[{playerId, truncatedEmail, countryCode, ggrAmount, totalWagered, currency}]}`, 10 each | game names from rounds; players every one |
| `GET /dashboard/activity` | — | `{events:[{eventId, eventType, playerId, truncatedEmail, amountMinorUnits:null, currency:null, occurredAt}]}`, today, newest first, 50 | — |
| `GET /dashboard/bonus-summary` | `currency` | `{activeBonusCount, totalLiability, bonusCostToday, totalLiabilityMinorUnits, bonusCostTodayMinorUnits, avgWageringCompletionPct, currency}` | both field-name sets; figures, not zeros |
| `GET /dashboard/payment-health` | `currency` | `{depositSuccessRatePct, avgWithdrawalHours, currency, pspBreakdown:[{pspName, depositVolume, depositCount, successRatePct}]}` | figures; `null` with no attempts |
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
| `GET /day-end/{day}` | `{day, zone, closedAt, reference, currencies:{<c>:{deposits:[{provider, count, amount}], withdrawals:[{gateway, count, amount}], games:[{provider, gameId, gameName, providerGgr, walletGgr}], walletGgr, depositPoolGgr, bonusCost, chargebacks, liability, pendingHolds, predictions?}}, differences:[{roundId, provider, providerStake, providerWin, walletStake, walletWin, status}], corrections:[{arrivedAt, day, figures}], openingCorrections:[…]}`; `409 day_open` before close |
| `GET /day-end/{day}/export` | the day-end as CSV, a document |
| `GET /compliance/rg-cases` | `from`, `to`, `format`: `{rows:[{playerId, type, openedAt, closedAt, outcome, reason}]}` |
| `GET /compliance/kyc-decisions` | `from`, `to`, `format`: `{rows:[{playerId, category, submittedAt, decidedAt, outcome, reason}]}` |
| `GET /predictions/summary` | `from`, `to`, `currency`: `{contractsTraded, notionalVolume, trades, traders, takerFees, resolutionPayments, remaindersKept, marketsOpened, marketsResolved, marketsVoided, marketsCorrected}` |
| `GET /predictions/markets` | `status`, `category`, `from`, `to`, `format`: `{markets:[{marketId, question, category, currency, status, openedAt, closedAt, resolvedAt, resolution, firstResolution, corrected, contractsTraded, notionalVolume, trades, traders, openInterest, takerFees, resolutionPayments, remainderKept}]}` |
| `GET /predictions/traders` | `from`, `to`, `currency`, `format`: `{traders:[{playerId, email, contractsTraded, notionalVolume, takerFees, positionsPaid, netResult}]}` |
| `GET /documents/{reference}` | the attachment when ready; `{reference, status, progress}` while generating; `410 document_expired` after |
| `GET /documents` | the records, newest first, `kind`, `from`, `to` |
| `GET`/`PUT /settings` | `ReportingSettings` in camelCase; `PUT` changes only the fields it names, and `null` clears an optional one (`defaultCurrency`); `400 invalid_setting` naming the field and its range |
| `GET /status` | `{generation, views:[{name, completeFrom, rebuilding}]}` |
| `POST /erasure/players/{playerId}` | `{service:"reporting", subject, documents}` |

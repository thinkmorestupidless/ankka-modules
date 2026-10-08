# Glossary

The words this project's features use, each in exactly one sense.

## Everyday words

a, an, the, and, or, of, to, in, on, for, with, is, are, was, has, have, had, does, not, no, one, two, any, every, each, same, other, its, their, this, that, when, then, after, before, within, again, still, only, also, than, more, less, new, first, last, next, named, called, shown, listed, given, asked, told, sent, kept, left, made, put, set, seen, found, open, closed, known, unknown, yes, number, amount, time, day, days, hour, hours, minute, minutes, second, seconds, year, years, month, months,
above, accepted, across, act, action, active, actor, add, added, address, addresses, admitted, against, ago, allowed, allowing, allows, already, ancestor, answer, anything, applicant, applied, applies, apply, applying, approval, approve, approved, approves, arrive, arrives, awaiting, awaits, back, backoff, backup, backups, bad, base, becomes, belonged, below, beside, between, bind, binds, birth, blocks, bound, browser, cache, caches, cancellation, cancelled, capture, captured, carried, carries, carry, category, chain, change, changed, changes, charged, checked, claimed, closure, comes, completed, computation, computed, concurrent, condition, conditions, configuration, confirm, confirmation, confirmed, confirms, consumed, content, continue, convert, converted, converts, copies, copy, correction, corrupted, counter, country, covers, create, created, creates, cross, cumulative, currencies, currency, cycle, daily, dashboard, date, dated, dates, debit, debited, debits, decision, default, deficit, delivered, delta, descendant, details, device, differ, difference, different, digest, discard, dismissed, dismisses, display, draw, drawn, duplicate, duplicated, during, earlier, earnings, earns, else, email, emit, emits, emitted, end, ended, ends, enforced, enter, entirely, entry, equals, erase, erased, erases, established, everything, exactly, exclude, excludes, exhausted, exists, export, exports, failed, fails, failure, falls, fetches, field, fields, figure, figures, file, files, filter, finance, floors, forgets, forms, forward, fresh, frontend, full, gains, gave, half, headline, held, however, identifies, identity, ignored, import, imported, inherits, inspects, interruption, invite, invited, issued, itself, keep, keeps, kind, knows, landed, landing, lands, lapses, late, learn, lets, life, lifetime, like, limit, local, locally, longer, major, maps, mark, marked, meaning, meeting, meets, minimum, minor, minus, mirrors, moved, need, never, nothing, occur, occurred, occurs, old, older, once, onward, optionally, organically, otherwise, outcome, outside, own, paid, part, partly, passed, passes, password, pay, pays, per, percent, percentages, permanently, persists, person, pins, placed, places, play, played, pointed, position, positive, postal, posts, previous, produced, provisioned, published, publishes, raised, ran, range, rate, reactivate, reactivates, read, readable, reader, readers, reading, reads, reason, rebuilds, rebuilt, received, receives, recomputed, reduced, refreshed, refreshes, refused, refuses, register, registered, registers, rejected, removed, replays, repository, request, requested, required, requires, resend, reset, restored, retried, retries, returned, returns, reverse, reverts, review, reviewed, revoke, revoked, right, rises, role, roles, rolled, row, rules, run, running, runs, say, says, scenario, see, sees, self, send, sender, sends, separately, served, serves, session, settled, settles, shows, sign, signature, signed, sorts, source, stale, stamped, stands, started, starts, states, stats, status, stay, stays, stores, strings, substituted, succeeds, suite, sum, summed, superseded, suspend, suspended, swept, telephone, tell, text, threshold, thresholds, total, totals, traffic, transactional, treated, turned, twice, unaffected, unchanged, unclaimed, unit, units, unless, untouched, usage, valid, verifies, verify, version, visible, visited, visitor, voided, what, where, which, who, whole, whose, withdrawn, without, won, writes, written, zero

## Terms

### player
*Proposed.* A person registered at one brand, with an account, wallets and personal data held under their id. A player belongs to one brand; the same person at another brand is another player.

### brand
*Proposed.* One commercial casino: its own players, operators, wallets, facts and versions, created from one manifest and running on its own. A brand answers for itself and for no other brand.

Avoid: project, tenant

### installation
*Proposed.* One deployment of the platform: every brand on it, the shared payments, the shared audit and the programme. A brand licensed elsewhere is a second installation.

### operator
*Proposed.* A member of staff who runs a brand or the installation: applies manifests, adjusts balances, adds networks, sees dead letters. Not a manager, who runs the programme.

### manifest
*Proposed.* The one description a brand is created from: its parts and their versions, the facts of the contract, what it allows the shared payments, the programme and the shared audit, and whether it takes real money.

### contract
*Proposed.* The list of facts every brand publishes and reads, each by kind, version and the part that publishes it. The one thing two parts of a brand agree on without reading each other.

### fact
*Proposed.* One statement that something happened, published by the part that owns it for any reader that needs it: a bet placed, a deposit confirmed, a player registered, a click. A fact has a kind, a version, an id that is the same every time it is emitted, and is about one thing so that the facts about it stay in order.

Avoid: notify, event, message

### view
*Proposed.* What a reader builds for itself from the facts it reads. A reader never reads another part's store; a view can be rebuilt from the facts at any time.

### shared payments
*Proposed.* The installation's one handling of payment providers, shared by every brand: it captures a deposit and hands it to the brand's wallet, and reports each payment's later outcome the same way. It holds a player's id and nothing else that identifies the player.

Avoid: merchant, psp-gateway

### shared audit
*Proposed.* The installation's one record of what every brand did, kept outside the brands and naming a player by a pseudonym and never by a real id.

Avoid: reasoner

### audit record
*Proposed.* One entry saying who did what, when, and what changed: written by every part for every money command and refusal, and by the programme for every account, membership, plan, payout, adjustment and subtree action.

### game provider
*Proposed.* The outside system that runs the games and, for each round, places the bet, credits the win, rolls the bet back or settles the round as a loss with the wallet.

Avoid: ST8, st8

### payment provider
*Proposed.* An outside system that moves money in or out for a player: it captures a deposit, pays a withdrawal, and reports outcomes to the brand by webhook.

Avoid: PSP, psp

### personal data
*Proposed.* A field that identifies a person, held under that person so that it can be erased: a name, an email address, a telephone number, a postal address, a date of birth, a document, a network address, a device identifier, free text written about the person, an affiliate's payment details. Amounts, currencies, ids, times, statuses, game identifiers, a country and a pseudonym are not personal data.

Avoid: PII, personal value

### erasure
*Proposed.* Making every field of personal data about a person unreadable, everywhere it is held, while every amount, id and time about them stays readable.

### retention hold
*Proposed.* A hold placed on an erasure request while a regulator or finance requires the records kept; when it lapses the erasure is applied without being asked again.

### original
*Proposed.* The platform that runs real money today, which the rebuild is held to: where a behaviour exists there, it is kept.

### recording
*Proposed.* What the original answered to one scenario of its own end-to-end suite, run locally with every provider stubbed, kept in this repository and replayed by a parity test.

### stub
*Proposed.* A stand-in for an outside system (a game provider, a payment provider) that answers as the real one would, so that the original can be run and recorded with no request leaving the machine.

### parity test
*Proposed.* A test of a rebuilt part that replays one recording and fails when the part answers differently from the original, naming the recording and the field.

### spec
*Proposed.* The description of one change to the system, naming the scenarios in the living features that hold it to account, and for every requirement what established it.

### wallet
*Proposed.* One player's ledger in one currency: real balance, restricted funds, open rounds, deposit turnover, the pending withdrawal request, and every transaction. A player with two currencies has two wallets. Created by its first deposit or award and never deleted.

### real balance
*Proposed.* The money in a wallet that is the player's own: deposited, won on real-funded bets, or converted from restricted funds. Only real balance can be withdrawn.

Avoid: cash balance, real wallet

### restricted funds
*Proposed.* Money a brand awarded a player that cannot be withdrawn until wagered: held as restricted funds records, drawn on by bets in priority order, converted to real balance when a record's release progress meets its wagering requirement, forfeited otherwise.

Avoid: bonus balance, bonus wallet, bonus funds, bonus money

### restricted balance
*Proposed.* The sum of the balances of a wallet's open restricted funds records. Derived, never commanded.

### restricted funds record
*Proposed.* One award's restricted funds in a wallet: its balance, wagering requirement, release progress, priority, open round count, the rules it was awarded under, and its state: active, awaiting its cap, forfeited or closed.

### award
*Proposed.* The giving of restricted funds to a player, which creates a restricted funds record; an award can be forfeited, cancelled with a clawback, or made primary.

Avoid: grant, bonus grant

### wagering multiplier
*Proposed.* The number an award's amount is multiplied by to give its wagering requirement; zero means the award converts on its first bet.

### wagering requirement
*Proposed.* The amount a restricted funds record's release progress must reach before it converts.

Avoid: release requirement

### release progress
*Proposed.* How much of a restricted funds record's wagering requirement has been met: advanced by each bet's contribution, reversed by a rollback, floored at zero.

Avoid: released progress, wagered progress

### contribution
*Proposed.* What one bet adds to a restricted funds record's release progress: the contribution base (the leg or the stake, capped at the record's maximum stake) times the contribution rate (a whole percent, by game, provider, category or default).

### win allocation
*Proposed.* The rule the primary restricted funds record sets for how a win is shared across the bet's legs: follow source (pro rata, the last restricted funds leg absorbing the rounding remainder) or all restricted (entirely to the highest-priority funding record).

### funding order
*Proposed.* The rule the primary restricted funds record sets for which source funds a bet first: real first, restricted first, proportional or combined.

### partial split
*Proposed.* Whether one bet may be funded partly from real balance and partly from restricted funds. With partial splits off, a bet real balance cannot cover alone is funded from restricted funds in full.

### priority
*Proposed.* The order restricted funds records are drawn on: lowest priority first, then earliest awarded.

### primary
*Proposed.* The first active restricted funds record in priority order: the one whose funding order and win allocation govern a bet, and the one an operator's adjustment or a provider credit lands on.

### leg
*Proposed.* The part of one bet funded from one source: the real leg from real balance, a restricted funds leg from one record. A win follows the legs back to their sources; a rollback restores each leg to where it came from.

### wallet type
*Proposed.* What funded a bet, as the original names it: real, bonus, split, or empty for a stake of zero. A win's wallet type mirrors its bet's.

### conversion
*Proposed.* What happens when a restricted funds record's release progress meets its wagering requirement: its balance is credited to real balance and the record awaits its cap.

### pending cap
*Proposed.* The state of a converted restricted funds record that stays open so a later withdrawal can cap what the player takes out; closed by closing the pending cap or when its remaining allowance is exhausted.

### maximum convertible amount
*Proposed.* The most a restricted funds record's conversion lets the player withdraw; the remaining allowance starts at it.

### remaining allowance
*Proposed.* How much of a converted record's maximum convertible amount a withdrawal may still draw on; a hold draws it down and voids the excess.

### deposits since conversion
*Proposed.* The deposits a player made after a restricted funds record converted and before its cap was settled; a hold draws on them before the remaining allowance.

Avoid: deposits since release

### forfeit
*Proposed.* Zeroing a restricted funds record and closing it as forfeited: by the brand's reward rules, by a hold under the record's withdrawal policy, by an operator's debit that leaves it at zero, or automatically when its balance is zero and its last open round settles as a loss.

### clawback
*Proposed.* An amount taken from real balance when an award is cancelled or forfeited by a hold, clamped to the real balance there is.

### withdrawal policy
*Proposed.* What a hold does to an active restricted funds record: none leaves it untouched; funds forfeits it; funds and winnings forfeits it and applies the clawback; cancel promotion forfeits it and reports the award for cancellation.

### scope
*Proposed.* The games, providers and categories a restricted funds record's rules include or exclude; its violation mode says what a bet outside the scope does.

### violation mode
*Proposed.* What a bet outside a restricted funds record's scope does: block refuses the bet; otherwise the record contributes nothing.

### maximum stake
*Proposed.* The largest bet a restricted funds record's rules allow, enforced as block (the bet is refused) or as cap (the contribution base is capped).

### open round count
*Proposed.* How many open rounds a restricted funds record has a leg in; a record at zero balance is forfeited when its count reaches zero on a loss.

### round
*Proposed.* One play of a game, opened by a bet and closed by a win, a rollback or a loss. A round is known to one wallet by its key.

### bet
*Proposed.* The stake a player puts on a round, funded from real balance and restricted funds by legs. The original's game provider calls it a debit.

### stake
*Proposed.* A bet's amount.

### win
*Proposed.* Money credited to a player for a round, following the bet's legs back to their sources. The original's game provider calls it a payout; in these features a payout is only an affiliate's.

### rollback
*Proposed.* The undoing of a bet: each leg restored to its source, each contribution reversed, the bet voided and the round closed. The original's game provider calls it a cancel.

### loss
*Proposed.* The settlement of a round in which the player won nothing: no money moves, the bet is marked as a loss and the round closes.

### transaction
*Proposed.* One entry in a wallet's ledger: its type, wallet type, amount, balance before and after, currency, status, references, reason, actor and created time.

### money command
*Proposed.* Any command that moves money in a wallet: a bet, a win, a rollback, a deposit, an award, a cancel, a hold, a release, a reversal, an adjustment, a provider credit. Every money command carries a key.

### key
*Proposed.* The name a caller gives one money command, under which sending it again returns the first outcome and moves nothing: the game provider's transaction id, the payment reference, the request id.

Avoid: idempotency key

### deposit
*Proposed.* Money a payment provider captured for a player, confirmed once to the wallet under its payment reference, crediting real balance and recording its required turnover.

### payment reference
*Proposed.* The id of one payment, which is the key of its deposit.

### deposit record
*Proposed.* What a wallet keeps about one deposit: its amount, turnover multiplier, required turnover and whether that turnover is pending or cleared.

### deposit turnover
*Proposed.* How much a player must wager from real balance before withdrawing: each deposit's amount times its turnover multiplier. Reduced by each bet's real leg, never restored by a rollback; a hold is refused while any is pending.

### withdrawal
*Proposed.* Money a player takes out of real balance: requested, held, then completed, cancelled, rejected, failed or reversed. Never funded from restricted funds.

Avoid: cashout

### hold
*Proposed.* The taking of a withdrawal's amount out of real balance while the request is pending; at most one hold is pending on a wallet. The original debits the request at once; a hold is that debit, named.

### release
*Proposed.* The return of a held amount to real balance when its request is cancelled, rejected or failed, clearing the pending request.

### reversal
*Proposed.* The return of a confirmed withdrawal's amount to real balance.

### adjustment
*Proposed.* A credit or debit a person with authority enters by hand with a reason: an operator's on a wallet's real balance or restricted funds, a manager's on an affiliate's ledger. Never counted as a deposit.

Avoid: admin adjust, manual adjustment

### provider credit
*Proposed.* A standalone amount the game provider credits outside a round: a free-spin win, a promotional payout. It lands on the primary restricted funds record if one is active, else on real balance.

### GGR
*Proposed.* Gross gaming revenue: total stake minus total win, per player and reporting day, as signed minor units.

### terminal
*Proposed.* The payment provider account a deposit came through, recorded on its transaction.

### affiliate
*Proposed.* A person or company that sends players to a brand and is paid commission for what they do. One account in the programme, with a membership per brand.

### network
*Proposed.* An issuer of tags and reader of facts: the programme, another installation's programme, or a third party. A brand knows a network by its id, its key document, its revocation list and the pseudonym key it holds for it.

### programme
*Proposed.* The installation's own affiliate network, shared by every brand: it issues tags, publishes its key document, revocation list and legacy tag document, reads every brand's facts, turns them into commission and pays affiliates. It knows a player only by a pseudonym.

Avoid: affiliate service, affiliate-service

### tag
*Proposed.* The value an affiliate's link or bonus code carries to a brand, signed by the network that issued it, naming the network, the affiliate, the link, a campaign, up to five sub-ids, the signing key and when it was issued. A brand verifies it with the network's key document and asks the network nothing.

Avoid: btag, token

### link
*Proposed.* An affiliate's tracking link to a brand, carrying a tag; clicks and registrations are counted against it.

Avoid: tracking link

### campaign
*Proposed.* An optional name an affiliate gives a link, carried in its tag and on every fact about a player it bound.

### sub-id
*Proposed.* One of up to five values an affiliate puts in a tag to tell their own traffic apart, carried on every fact about a player the tag bound.

Avoid: subid

### click
*Proposed.* One landing of a visitor on a brand with a valid tag, at most one per link per browser session: the tag's fields, the click id, the device type, the country and a digest of the visitor's address, never the address.

### click id
*Proposed.* The id of one click: the one the link brought, or one the brand assigned when the link brought none. Carried from the click to the binding and into every fact about the player.

Avoid: clickId

### attribution window
*Proposed.* How long after a tag was issued it may still bind a player at registration, and how long after an organic registration a bonus code may still bind one. A brand setting with an installation default.

### binding
*Proposed.* A player's attribution to a network, affiliate, link, campaign and sub-ids, with the click id and the pseudonym, made once at the first of a valid tag or an affiliate's bonus code, and never changed.

### organic
*Proposed.* A player bound to no affiliate: registered with no tag, a stale tag, a refused tag or a revoked link.

### later touch
*Proposed.* A tag, bonus code or link presented for a player already bound: recorded on the attribution record with its time and changing nothing.

### attribution record
*Proposed.* What a brand keeps about one player's attribution: the binding, the refused or stale tag when there was one, and the later touches. Part of the brand's regulated books; nothing a network says changes it.

### attributed
*Proposed.* Of a player: bound to an affiliate, so that every fact about them names that affiliate. Of a fact: naming the affiliate the player is bound to.

### unattributed
*Proposed.* Of a fact the programme received: naming a player it has no attribution for at that brand, a revoked link, or an affiliate with no membership there; recorded with its reason for a manager to see and writing no commission.

### pseudonym
*Proposed.* The only name of a player a network ever sees: a keyed digest of the player's id under the brand's pseudonym key for that network, stable for the player's life, different at every brand and every network, and surviving erasure.

Avoid: hashed id, pseudonymous id

### pseudonym key
*Proposed.* The secret a brand holds for one network, under which it derives its players' pseudonyms for that network; never rotated and never leaving the brand.

### signing key
*Proposed.* A key under which a network signs tags, or a brand signs feed pages and webhooks; published by id in a key document and withdrawn from it when retired.

### key document
*Proposed.* What a network or a brand publishes listing its current signing keys by id and how long a reader may cache it.

### revocation list
*Proposed.* What a network publishes naming the links and affiliates whose tags bind no new player; a brand refreshes it on the stated interval.

### legacy tag
*Proposed.* A tag from before this protocol: a bare id with no network, sub-ids or signature, resolved through the programme's legacy tag document during the transition period and refused after it.

Avoid: bare UUID

### legacy tag document
*Proposed.* What the programme publishes during the transition period mapping each imported legacy tag to its affiliate and link; withdrawn when the period ends.

### transition period
*Proposed.* The time from a brand's first day on this protocol during which a legacy tag is still accepted.

### reverse channel
*Proposed.* The whole of what a network may tell a brand: its key document and its revocation list. Nothing else is accepted; nothing a network says changes a binding, a fact or an amount.

### carrier
*Proposed.* One way a reader receives a brand's facts: the programme's own reading when the brand allows it, a machine reading with its own reader, the feed, or a webhook. Every carrier delivers at least once; a reader discards a repeat by its fact id.

### feed
*Proposed.* The brand's facts for one network served in the order they were emitted, from a cursor, in signed pages, to a machine the installation registered.

### cursor
*Proposed.* An opaque position in a brand's feed; reading from the same cursor twice returns the same page.

### webhook
*Proposed.* A network's address and shared secret a brand's operator created, to which the brand posts each fact signed, retrying with backoff for a day before holding the fact in a dead letter.

### dead letter
*Proposed.* Where a brand holds a fact a webhook failed to receive for a day, for the operator to see, resend or discard.

### machine
*Proposed.* A network's own system registered with the installation, admitted to a brand's facts and nothing else of the brand's.

### protocol
*Proposed.* The agreement between a brand and any network: the tag, the facts, the pseudonym, the carriers and the reverse channel. Facts carry its version; a change to a fact's shape is a new version emitted beside the old.

### bonus code
*Proposed.* A code an affiliate hands out instead of a link; a player who presents it is bound to the affiliate as a tag would bind them. Unique per brand regardless of case.

Avoid: promo code, promotion code

### bonus cost
*Proposed.* What a brand spent on a player's rewards: emitted as a fact when restricted funds are awarded, forfeited or converted, and folded into NGR by the programme.

### chargeback
*Proposed.* A deposit the player's payment provider took back; emitted as a fact and folded into NGR.

### reward
*Proposed.* What a brand gives a player under its reward rules: restricted funds, free spins, a bonus code's award.

### round stream
*Proposed.* A brand setting per network that emits a fact for every settled round beside the daily GGR fact.

### reporting day
*Proposed.* One day in the brand's reporting zone; when it closes, one daily GGR fact is emitted per attributed player with activity.

### self-exclusion
*Proposed.* A player's own permanent bar from a brand; emitted to the programme as a closed player with the category self-excluded.

### account
*Proposed.* One affiliate's standing in the programme: identity and payment fields as personal data, a parent and an override, and a membership per brand.

Avoid: affiliate registration

### membership
*Proposed.* An account's standing at one brand: pending, active, suspended or rejected, with the plan at that brand and the terms accepted for it.

### application
*Proposed.* An affiliate's request to join a brand, which creates the account when the email is new and otherwise adds a pending membership; refused when the email already has a pending or active membership at that brand.

### manager
*Proposed.* A member of staff who runs the programme for the brands their roles cover: approves applications, manages plans and settings, closes periods, reviews fraud flags.

### plan
*Proposed.* A brand's commission terms a membership is on: CPA, RevShare or hybrid, with the CPA amount and currency, the RevShare percentage, the minimum deposit for CPA and whether negative NGR carries over. A brand's settings name the default plan for new memberships.

### CPA
*Proposed.* Commission paid once for a player's first deposit at or above the plan's minimum deposit, in the plan's currency.

### RevShare
*Proposed.* Commission paid as a percentage of a player's cumulative NGR, written as the delta between the commission due and what has already been emitted.

### hybrid plan
*Proposed.* A plan that pays both CPA and RevShare.

### NGR
*Proposed.* Net gaming revenue: GGR minus bonus cost, chargebacks and the brand's admin, payment and provider fee percentages.

### negative carryover
*Proposed.* Whether a period's negative NGR persists as a deficit into the next period, or the period's RevShare floors at zero and the next starts fresh.

### first-deposit guard
*Proposed.* The programme's record that CPA was paid for a player at a brand, set once for the player's life and never by a first deposit below the plan's minimum deposit.

### commission
*Proposed.* What an affiliate is owed for the players they sent: CPA, RevShare, override and adjustments, as payable facts in a ledger.

### ledger
*Proposed.* The immutable facts for one affiliate at one brand in one currency: NGR inputs and payable facts, each never changed except to record the payout that claimed it.

Avoid: commission account

### payable fact
*Proposed.* A ledger fact that is owed to an affiliate: CPA, RevShare, override or an adjustment; claimed once by a payout when a period closes.

### fold
*Proposed.* The ledger computed fresh from its facts, every time, so late and out-of-order facts correct themselves and nothing is counted twice. Every read of revenue is a fold.

### revenue
*Proposed.* What the portal shows an affiliate or a manager: the fold of a ledger over a period, per brand and per currency, never summed across currencies.

### period
*Proposed.* The span a brand's payout cycle closes at once: a month, a month range, a day range in the brand's reporting zone, or all time.

### close
*Proposed.* A manager's closing of a period for a brand: every unclaimed payable fact up to the period's end is claimed by a pending payout per affiliate and currency. Repeating a close claims nothing twice.

### payout
*Proposed.* What one affiliate is paid for one period at one brand in one currency: pending, approved, rejected or paid; reverted to pending when a later close changes its total; never changed once paid. In these features a player's money out is a withdrawal, never a payout.

### override
*Proposed.* A parent's percentage of what each affiliate below them earns, written as an override fact per ancestor and source, recomputed as a delta when the source's earnings change.

### parent
*Proposed.* The affiliate above another in the subtree: invites subs, approves, suspends and reactivates them, changes their plan and override, and earns override on them.

### sub
*Proposed.* An affiliate below a parent in the subtree, created pending under the parent by an invitation.

Avoid: child affiliate

### subtree
*Proposed.* An affiliate and every affiliate below them; what an affiliate sees and acts within, and nothing above or beside.

Avoid: network tree, downline

### invitation
*Proposed.* A parent's one-use offer to a sub, by email or invite link, which creates the sub pending under the parent on the parent's plan unless it says otherwise.

### fraud flag
*Proposed.* A warning the programme raises once per player, brand and reason when a brand's bonus cost or chargeback thresholds are crossed, for a manager to review and dismiss.

### programme page
*Proposed.* A brand's public terms, contact, payout cycle and description that applicants see.

### portal
*Proposed.* Where an affiliate signs in to see their memberships, revenue, breakdown, payouts, players, links, bonus codes, subtree and notifications, for one brand or for all.

### notification
*Proposed.* A message to an affiliate in the portal: application received, approved or rejected, payout paid, a sub pending approval, a fraud flag dismissed, an invitation.

### postback
*Proposed.* A call the programme makes to an affiliate's own address when a registration, first deposit, deposit or commission fact occurs for their player, with the kind, click id, payout amount and status substituted; retried with backoff for a day.

Avoid: callback

# ankka-casino — agent context

eitheror's casino and payments verticals, rebuilt as domain services on
[ankka](../../thinkmorestupidless/ankka) and operated with the ankka runtime. This replaces the
generic twin substrate (concepts, TL functions, Go action handlers, Neo4j, Cloudflow) that
`eitheror-platform` runs today. The eitheror brain is loaded from the parent directory's
`CLAUDE.md`; this file adds what is specific to this repository.

## Read first

- `brain/projects/ankka-casino.md` (in the eitheror brain) — status, people, decisions.
- The plan: [eitheror on ankka: casino and payments build plan](https://claude.ai/code/artifact/d8568586-5a81-465f-a8d5-a87722d235c1)
  — projects, services, component tables, topic contract, phases. Decisions live in its comment threads.
- The platform features it depends on: [ankka core features for eitheror](https://claude.ai/code/artifact/568e0f36-01dc-45f0-a2f2-1ba40ae93623)
  and ankka specs 038–044 (`ankka/specs/`, on their branches until merged).
- `.specify/memory/constitution.md` — the rules every spec here is held to.
- `GLOSSARY.md` and `features/` — the domain's words and what the system does now.

## The original

`~/Documents/projects/eitheror/eitheror-platform` is the running, real-money original (Spinvibe).
Its behaviour is the specification (constitution I). When writing a spec for a service here, read
the original's code for that capability, not only the plan: `substrate/<name>-service/`, the TL
packs under `tl/`, the Go handlers, and `specs/` (its own spec-kit history, numbered into the 200s).
`~/Documents/projects/eitheror/platform` is an older, different repository; ignore it.

## Specs, in build order

| Spec | Project | Service or capability |
|---|---|---|
| 001 | all | brands and projects: layout, manifests, topic contract, realms, the port method |
| 002 | brand `money` | wallet |
| 003 | brand `casino` | game-provider (ST8 and the generic provider API) |
| 004 | shared `payments` | merchant (hierarchy, deposit workflow) |
| 005 | shared `payments` | psp-gateway (nine adapters) |
| 006 | shared `payments` | ingest (webhooks) |
| 007 | brand `money` | payouts |
| 008 | brand `casino` | players |
| 009 | brand `casino` | kyc |
| 010 | brand `casino` | responsible-gambling |
| 011 | brand `casino` | rewards |
| 012 | brand `casino` | affiliate-protocol (attribution tag and facts a brand emits) |
| 013 | shared `programme` | affiliate-programme (accounts, plans, ledger, payouts, portal) |
| 014 | brand `casino` | engagement |
| 015 | brand `casino` | reporting |
| 016 | brand `casino` | partner-api |
| 017 | brand `casino` | support-agent |
| 018 | shared `reasoner` | audit and proposals |
| 019 | all | the Spinvibe migration |

## Working here

- Spec-kit with the bdd extension: `/speckit-specify` writes a spec, `/speckit-bdd-features` moves
  its scenarios into `features/`, `just features` checks them (needs `uv`).
- Build services with the `ankka@ankka` skills plugin and `ankka init`; port with `ankka-port`
  (record the original locally, write `surface.md` and a spec from the recordings, keep one parity
  test per recording) and check a running service with `ankka-inspect`.
- Scala by default; British spelling; `eitheror` lowercase; money in minor units as `Long`.

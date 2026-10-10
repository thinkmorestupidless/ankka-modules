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

| Spec | Project (one per brand, named by its slug; shared projects named) | Service or capability | Language |
|---|---|---|---|
| 001 | all | brands and projects: layout, manifests, topic contract, realms, the port method | — |
| 002 | brand | wallet | Scala |
| 003 | brand | game-provider (ST8; the generic provider API was a stub and is dropped) | Scala |
| 004 | shared `payments` | merchant (hierarchy, deposit workflow; serves `ingest` and `psp-gateway` only) | Scala |
| 005 | shared `payments` | psp-gateway (eight adapters; ConnecX dropped) | TypeScript |
| 006 | shared `payments` | ingest (the merchant-facing edge: cashier routes under a merchant key) | Scala |
| 007 | brand | payouts | Scala |
| 008 | brand | players | Scala |
| 009 | brand | kyc | Scala |
| 010 | brand | responsible-gambling | Scala |
| 011 | brand | rewards | Scala |
| 012 | brand | attribution (the affiliate protocol a brand emits) | Scala |
| 013 | shared `programme` | affiliate-programme (accounts, plans, ledger, payouts, portal) | Scala |
| 014 | brand | engagement (CRM, suppression, transactional sends, push, presence) | Scala |
| 015 | brand | reporting (views, dashboard, day-end, compliance exports) | Scala |
| 016 | brand | partner-api (built last, not in the pilot; only when a partner is contracted) | TypeScript |
| 017 | brand | support-agent (Motoko on ankka's agent model; off per brand by default) | Scala |
| 018 | shared `reasoner` | audit (proposals are not carried over) | Scala |
| 019 | all | the Spinvibe migration | — |
| 020 | `tools` | game-provider-simulator: ST8's protocol, scripted, for parity and failure tests | Scala |

## Working here

- The root build holds two things: `contract/` (`io.eitheror:casino-contract`: the facts on every
  topic, money in minor units with keys, the six route classes, the personal-data markers, the
  recording replayer) and `tools/brands/` (the tool that applies a brand's or a shared project's
  manifest). `just build`, `just test`, `just schemas`, `just brands …`, `just it` (integration, on
  purpose only: it creates projects on the installation `ANKKA_URL` names).
- A service is one sbt build of its own under `services/<name>/` (`ankka init`), depending on the
  published contract (`sbt contract/publishLocal`). It declares ACLs only through
  `contract.access.Routes`, money only through `contract.money`, facts only through `contract.facts`.
- `brands/<slug>/manifest.json` and `shared/<project>/manifest.json` are how anything is deployed;
  `installation/overlays/<name>/settings.env` is what decides whether a brand may take real money.
- `tests/original/` records the original (`README.md` there); `tests/port/<service>/` holds the
  recordings a service's parity tests replay.
- The `ankka@ankka` plugin must be installed (`/plugin install ankka@ankka`) for `ankka-port` and
  `ankka-inspect`.

- Spec-kit with the bdd extension: `/speckit-specify` writes a spec, `/speckit-bdd-features` moves
  its scenarios into `features/`, `just features` checks them (needs `uv`).
- Build services with the `ankka@ankka` skills plugin and `ankka init`; port with `ankka-port`
  (record the original locally, write `surface.md` and a spec from the recordings, keep one parity
  test per recording) and check a running service with `ankka-inspect`.
- Scala by default; British spelling; `eitheror` lowercase; money in minor units as `Long`.

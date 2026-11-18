# ankka-modules

eitheror's casino, payments and prediction markets, as modules of services on
[ankka](https://github.com/thinkmorestupidless/ankka). Until 2026-10-21 this repository was
`ankka-casino`; the brain's decision 0055 says why it was renamed and laid out as a core and
product modules, which spec 021 builds.

Each brand is an ankka project of its own; payments, the affiliate programme, the reasoner and the
house provider's games (spec 026) are shared projects. A brand runs the `core` and the modules ankka-vendor entitles it to, each described
by one manifest under `modules/` and applied by the vendor's agent (spec 021). The specs under `specs/` describe each service; `features/` holds what the system
does, in the words `GLOSSARY.md` defines. `CLAUDE.md` says how to work here.

Services build on two libraries published from the root build: the contract and the provider core
(`provider-core/`, shared by the game provider simulator and the house provider). `just service
<name> <sbt task>` publishes both, then runs the task in that service's build.

The player SDK (`sdk/player/`, spec 027) and the reference player site built on it
(`services/player-site/`) are TypeScript, in the npm workspace at the root (`npm ci`).
`just player-sdk-test` runs the SDK's suites, `just player-sdk-features` binds
`features/player-site/` to its behaviours with no browser, `just player-site-run` runs the site on
this machine, `just player-site-features` runs `reference-site.feature` against the built site,
and `just player-site-it` runs the client contract tests against an installation.

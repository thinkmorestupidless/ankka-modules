# ankka-modules

eitheror's casino, payments and prediction markets, as modules of services on
[ankka](https://github.com/thinkmorestupidless/ankka). Until 2026-10-21 this repository was
`ankka-casino`; the brain's decision 0055 says why it was renamed and laid out as a core and
product modules, which spec 021 builds.

Each brand is an ankka project of its own; payments, the affiliate programme and the reasoner are
shared projects. A brand runs the `core` and the modules ankka-vendor entitles it to, each described
by one manifest under `modules/` and applied by the vendor's agent (spec 021). The specs under `specs/` describe each service; `features/` holds what the system
does, in the words `GLOSSARY.md` defines. `CLAUDE.md` says how to work here.

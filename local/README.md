# local

A brand as one project on this machine, run with `ankka local up` (docker compose): the brand's
services, the shared services it calls (payments, the house game provider, the game provider
simulator), an identity provider and the payment provider stub. It stands in for a brand on
`dev-kind` where a whole cluster is not needed (spec 027, T004 to T006, T060, T051).

```bash
just local-images                      # every image it runs, built from this branch as 0.0.0
just local-project                     # local/guyanabet/ from the brand's plan (local/generate.py)
cd local/guyanabet && ankka local up   # everything; a second `up` restarts only what changed
cd ../.. && just local-onboard         # realms, operator, payments, game providers; once
just player-site-it                    # the contract tests, then the timed browser run
```

The site is at http://localhost:3310, the identity provider at http://localhost:18180 (admin,
admin), each service at the port `local/generate.py` names (`PORTS`).

## What differs from the brand on an installation

`local/generate.py`'s docstring is the list: addresses, local-only secrets, the support agent,
`payouts` and `attribution` left out, `kyc` without a bucket, and ST8 played by the simulator as a
second provider configuration `st8`. Nothing here reaches ST8 or any payment provider.

## Things to know

- `local.json` is generated and ignored: it holds the cashier key this machine's `merchant`
  issued, kept in `guyanabet/.ankka/local/merchant-key.env`. Before onboarding, a deposit is
  refused.
- Staff tokens last about 5 minutes: `guyanabet/operator-token.sh` writes a fresh one, which the
  onboarding scripts and the contract tests read.
- Run `ankka local up` with no names. With names it stops every other service (their data is kept).
- 13 JVMs need more than Postgres's default 100 connections. Once, with the project up:
  `docker exec <postgres container> psql -U ankka -d postgres -c "alter system set max_connections = 400"`,
  then `ankka local down` and `up`.
- Every service is on ankka 0.18.0 (spec 031), so `ankka local up` needs the 0.18 CLI. Before it,
  the services on 0.13 (merchant, psp-gateway, ingest, provider, the simulator) showed "running, not
  ready", because that runtime served no readiness port; check that none still does.
- The local proxy has no gateway in front of it, so it passes on no visitor address and players
  finds no country: the registration form asks for one.

# Shared projects

The installation's five shared projects (spec 001 FR-003), created by the same tool as a brand:
`brands apply shared/<project>/manifest.json --installation <name>`. A shared project's grants name
`{brand}`; the tool makes one per brand under `brands/`. Their services' images come from the specs
that build them (004–006, 013, 018, 020, 026). `games` holds eitheror's own game provider (spec 026), which a brand reaches as it reaches ST8. `tools` holds the game provider simulator (spec 020) and is refused on an installation that takes real money.

A brand's backoffice (spec 030) calls `payments` and `programme` with the signed-in operator's
token, under no grant; the brand's value `backoffice-shared-projects` names the project each is in.
Spec 030 adds one grant: `payments` lets each brand's `players` call `merchant`'s
`PUT /v1/operators/{id}/brand-roles/{brand}`, which sets the roles a brand gives an operator.

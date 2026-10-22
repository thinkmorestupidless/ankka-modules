# Modules

A brand project runs `core` and the modules its entitlement at ankka-vendor names, no more (spec 021,
decision 0055). Each module is one manifest here, `modules/<module>/module.json`, in ankka-vendor's
manifest format (vendor spec 004 `contracts/manifest.md`; the schema is vendored in
`tools/modules/src/main/resources/ankka-vendor-manifest.schema.json`).

| Module | Kind | Services | Depends on |
|---|---|---|---|
| `core` | core | wallet, players, kyc, responsible-gambling, payouts, attribution | |
| `casino` | product | game-provider, rewards | `core` |
| `engagement` | product | engagement | `core` |
| `reporting` | product | reporting | `core` |
| `support` | product | support-agent | `core` |

`partner` (partner-api, on `casino`), `predictions` and `pool` are written by specs 016, 023 and 024.

## What a manifest carries

Services with their descriptors, topics, the grants the module makes and receives, its dependencies
with version ranges, the configuration keys a brand must supply by name, its retention after disable
and any condition on ending. Never a value: a service's `env[]` names a key (`{"name", "key"}`) from
`contract/keys.md` or a literal that is no secret, and images carry `0.0.0` until the release renders
the tag's version.

## Adding a module

1. Add its keys to `contract/keys.md`, its services to that file's "Not yet built" table until their
   builds exist, and any topic to the contract's families.
2. Write `modules/<module>/module.json`; a product module depends on `core` and on no other product
   module (`partner` on `casino` is the one exception).
3. `just modules validate --all`.

## What the tag does

One `v*` tag builds every service's image and publishes every manifest at the tag's version to
ankka-vendor; it moves no channel (`contracts/release.md` of spec 021).

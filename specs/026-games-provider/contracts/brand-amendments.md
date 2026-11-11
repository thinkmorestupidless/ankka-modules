# Contract: the amendments to specs 003, 009 and 001 (FR-026 to FR-028)

## Spec 003 — a provider's protocol is configuration (research R11)

A new brand value, in `contract/keys.md` and the `casino` module's manifest:

| Key | Default | Meaning |
|---|---|---|
| `casino-providers` | `st8:st8,eitheror:st8` | the provider names the brand's `game-provider` serves, each `name:protocol`; `st8` is the only protocol |

At start `Settings.adapters = names.map(St8Adapter(_))`; `ProviderPathEndpoint(name)` per name
(`/eitheror/{callback}` for the house provider); `RootCallbackEndpoint` for `st8` only.
`ProviderConfiguration` is unchanged in the secret store; its operator read
(`GET /v1/game-provider/{brand}/providers/{name}`) gains `operatorPublicKey`, derived from the
private key, so an operator can copy it into the provider's brand entry. A configuration written
for a name not in `casino-providers` is refused naming the value.

Spec 003's text: FR-026 gains the sentence "a provider's protocol is `st8` for every provider a
brand runs; the names a brand runs are its `casino-providers` value"; the `fake-st8` and simulator
set-ups are unchanged.

## Spec 009 — the Anjouan profile (research R13)

```scala
case Anjouan extends Profile("ANJOUAN")
val Anjouan: Policy = Curacao.copy(profile = Profile.Anjouan)
byName += "ANJOUAN" -> Anjouan
```

The unknown-profile refusal now names four profiles. Spec 009's *Changes from the original* gains:
"Anjouan added (spec 026); the known-profile list diverges from the original's recording by that
name." Glossary `licence profile` already names it.

## Spec 001 — the fifth shared project

- `contracts/shared-manifest.schema.json`: project enum `+ games`; services enum `+ provider`
  (027: `+ pools`).
- `tools/modules/src/main/resources/shared-manifest.v1.json`: the same.
- `contract/.../Layout.scala`: `SharedProjects += "games" -> Vector("provider")`.
- Spec 001 FR-003's sentence counts five shared projects.
- `brands/_realm`: the `games` realm role beside `markets`.

## The brain

`brain/projects/ankka-modules.md` gains the house provider and this spec; `brain/company/identity.md`
or `positioning.md` gains one line: the brands run under an Anjouan licence into South American
markets (Spanish and Portuguese). Both are in the task list, not in this repository's build.

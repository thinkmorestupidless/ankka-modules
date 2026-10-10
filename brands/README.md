# brands

One directory per brand, each holding the one manifest the brand is created from (spec 001 FR-007).
The schema is `specs/001-brands-and-projects/contracts/manifest.schema.json`; the tool that applies it
is `tools/brands` (`just brands …`).

## What a manifest holds

- `brand`: the slug (the project id: lowercase, at most 42 characters, not a shared project's name),
  the name, whether it takes real money, and optionally its player site's address.
- `ankka.runtime`: the ankka version every service is built against.
- `keycloak`: where the casino's realms are, and the audiences their tokens carry.
- `services`: every service of FR-002 at a pinned image tag (never `latest`), whether it is exposed,
  and its extra environment. The pilot leaves out `partner-api` and ships `support-agent` off.
- `topics`: the ten topics of the contract with their partitions and retention.
- `grants`: what the brand grants the shared projects, and what it expects from `payments`.
- `secrets`: names and keys only.

## Adding a brand

1. Copy `goldvibe/` to `<slug>/` and change the slug, the name and the received grants' principals.
2. Set each service's image to the versions this brand starts on.
3. Put the brand's secrets in a file outside the repository (`<secret>/<KEY>=<value>`).
4. `just brands validate brands/<slug>/manifest.json`, then
   `just brands apply brands/<slug>/manifest.json --installation <name> --secrets <file>`.
5. Apply each `shared/<project>/manifest.json` again, so the shared projects grant to the new brand.

## A release reaches one brand first

A release is a pull request changing one brand's image tags; applying that manifest is the deploy, and
no other brand moves until its own manifest changes (FR-008). Rolling back is reverting the pull
request and applying again. An operator's emergency `ankka services rollback` is respected: the next
apply reports `manifest behind cluster` rather than redeploying over it.

## Taking real money

A brand's `realMoney` flips to `true` only in a release pull request whose check passes:

```bash
just brands validate brands/<slug>/manifest.json --installation gke --real-money
```

It refuses, naming the condition and where it was read, until the installation keeps an off-cluster
backup, erases personal data with a keyring of two, may keep facts as long as the contract needs, and
keeps three copies of each on three broker nodes (FR-035 to FR-038). There is one manifest per brand;
no second file for production.

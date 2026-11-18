# player-site

The reference player site (spec 027): eitheror's own site on the player SDK
(`sdk/player`, `@eitheror/player-sdk`), the one service of the module `player-site`. Its process
is the SDK's site server over the built pages; its pages are appearance, words and the choice of
which behaviour to show, and hold no rule of a deposit, a launch, a sign-in or an amount (FR-046).

## Run

```sh
npm install                  # at the repository root: the workspace holds the SDK and the site
npm run build -w player-site
ankka local web -- npm start -w player-site
```

The site server reads `CASINO_BRAND`, `CASINO_MODULES`, `PLAYER_SITE_LANGUAGES`,
`PLAYER_SITE_CURRENCY`, `PLAYER_SITE_BASE`, `INGEST_URL` and the platform's `ANKKA_SERVICES_URL`
(`sdk/player/src/server/config.ts`); `PLAYER_SITE_LOCAL=true` drops the `__Host-`/`__Secure-`
cookie names for plain `http://localhost`.

## Test

```sh
npm test -w player-site      # the words, the import rule, the second appearance
```

## Image

Built from the repository root, so the workspace is in context:

```sh
docker build -f services/player-site/Dockerfile -t player-site .
```

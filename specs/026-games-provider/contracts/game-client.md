# Contract: a game's client (FR-015, research R12)

## Layout

```text
services/<game>/
├── client/
│   ├── package.json            # vite, typescript; scripts: dev, build, test
│   ├── vite.config.ts          # base: '/game/', build.outDir: '../src/main/resources/client', assetsDir: 'assets' (flat)
│   ├── tsconfig.json
│   ├── index.html
│   └── src/
│       ├── main.ts             # reads ?token and ?lang; calls GET /game/session?token=…; renders
│       ├── session.ts          # the game service's session answer (brand, currency, language, demo)
│       ├── money.ts            # minor units → display by the currency's exponent (Intl.NumberFormat)
│       └── i18n/{es,pt}.json
├── src/main/resources/client/  # built output, gitignored
└── src/main/scala/.../web/GamePageEndpoint.scala   # GET /game, GET /game/assets/{file}, GET /game/session
```

## Build

```bash
just service <game> client      # cd services/<game>/client && npm ci && npm run build
just service <game> Docker/publishLocal   # runs `client` first
```

CI: the game's job adds `actions/setup-node@v4` (Node 22) before `sbt test`; `npm run build` runs
in the job so the image never ships without the client. A game's `sbt test` fails when
`src/main/resources/client/index.html` is missing.

## Serving

| Route | What |
|---|---|
| `GET /game?token=<launch ticket>[&lang=es\|pt]` | `index.html`; the token stays in the query for `main.ts` |
| `GET /game/assets/{file}` | the built assets, one path parameter, content type by extension, cache for a day |
| `GET /game/session?token=` | the game service asks the provider's check (contracts/game-contract.md), then answers `{gameSessionId, brand, currency, language, demo, balance?}`; a refusal answers `{refused}` and the page shows the session-expired view |

The page is public, admitted by its launch ticket (spec 001 FR-020's public class), as the
stand-in's is. Nothing in the client holds a key or calls the brand.

## Language and money

- Language: the session's (`language` from the check), overridden by `?lang=`; bundles `es` and
  `pt` are required of every game; a missing key falls back to `es`.
- Money: `Intl.NumberFormat(locale, {style: 'currency', currency})` over `amount / 10^exponent`;
  the exponent comes with the session (the provider reads it from the contract's `money`
  currencies table; CLP is 0, BRL 2).

## Spec 027

Writes the first client to this layout; this spec builds none and tests the layout by the recipe
and the serving routes in the test game's place only as documentation.

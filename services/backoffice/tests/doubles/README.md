# The services double (T046)

One process at the calling address. The first path segment names the owner, as the platform's proxy
reads it: `/players/…` is the brand's `players`, `/merchant.payments/…` is the shared project's
`merchant`. There is one owner for every service the route table (`src/server/routes.ts`) names, and
one for `backoffice-state`. There is no document store: kyc answers an identity document's bytes itself
(amendment K5), so its double does too. An unknown segment gets the proxy's answer: `503`, `X-Ankka-Answered-By: proxy`.

```bash
npm run doubles                               # DOUBLES_PORT, 4710 by default
ANKKA_SERVICES_URL=http://127.0.0.1:4710 CASINO_BRAND=goldvibe \
  ANKKA_AUTH_STAFF_ISSUER=http://localhost:4710/realms/eitheror BACKOFFICE_HOST=http://localhost:4700 \
  BACKOFFICE_SHARED_PROJECTS=payments=payments,programme=programme,reasoner=reasoner,games=games \
  PORT=4700 node src/server/main.ts           # then open http://localhost:4700
npx vitest run --dir tests/doubles            # the doubles' own checks, through the real server
```

`npm run features` and `npm run a11y` start both themselves (`env.ts`), and reuse them if they are
already running outside CI.

## V3: no `ankka local web`

Checked on 2026-11-15 with ankka 0.16.3. `ankka local web --service merchant.payments=<url>` is
accepted, but a call to `$ANKKA_SERVICES_URL/merchant.payments/…` is still answered by the proxy:
`503 {"error":"no service 'merchant' in the project 'payments'"}`. `--service payments/merchant=<url>`
is answered the same way. `--service merchant=<url>` does serve the dotted call, because the project
part is ignored locally, so the brand's `merchant` and `merchant.payments` cannot be told apart. The
proxy adds nothing the server needs: it passes `Authorization` as given. So the double listens under
the dotted name itself, and the server runs directly with `ANKKA_SERVICES_URL` at the double.

## Fixtures

`fixtures/<owner>.json` maps `"<METHOD> <the owner's path template>"` to an answer
`{status?, body, headers?}`, or to `{by: "<parameter>", answers: {<value>: answer}, otherwise?}` (`by: "?<name>"`
answers by a query parameter, `""` when it is absent). A
literal path goes before a parameter in the same place. An operator route needs `Authorization:
Bearer …` and answers `401` without it, unless its entry is `anonymous` (the server's own reads of the
brand's roles and modules). A route with no fixture answers `501 {"error":"no_fixture"}`. The brand's
roles come from the contract's defaults (`operator-permissions.v1.json`) unless `players.json` names
them. `fixtures/brand.json` is the seeded brand: `goldvibe`, zone `America/Sao_Paulo`, currencies
BRL, EUR, JPY and KWD, four players, and the default preferences. `fixtures/scripted/<name>.json` holds
answers a test asks for by name.

## Control (`/__double/…`, through `client.ts`)

| `DoublesClient` | Does |
|---|---|
| `reset()` | every owner back to its files; scripts, calls, sessions and preferences forgotten |
| `fixtures(owner, entries)` | entries added over the files for one scenario |
| `fail(owner, status, {method, path, times, body})` | answers the calls that fit with that status |
| `slow(owner, ms, …)` | waits before answering |
| `answer(owner, name, …)` | answers `fixtures/scripted/<name>.json` |
| `script(owner, {proxy: true})`, `{drop: true}` | the proxy's own answer; a closed connection |
| `calls(owner, {method, path})` | the calls received, each with every header and its body |
| `expectAuthorizedAs(owner, sub, …)` | throws unless every call that fits carried `sub`'s token |
| `preferences(subject, p)`, `roles(subject, roles)`, `tokenLife(ms)` | the double `backoffice-state` |
| `fraudCases({cases})` | players' fraud cases kept from here on: listed by status, reviewed, dismissed; a dismissal clears the suspected duplicate flag on the profile of every player the case links, as players does |
| `wallets({wallets})` | the wallet's balances kept from here on: the balance read, an adjustment under its key (a repeat answers its first outcome, another body under it `KEY_REUSED`), a debit past the balance refused, the transactions read; `refuse` on a wallet answers its every adjustment with that refusal |

A path in a script is an exact path or a template. The newest script that fits wins; `times` bounds it.

## The double realm

`/realms/eitheror/protocol/openid-connect/auth` shows no login page. It reads the operator from the
`double-realm` cookie (`sign-in.ts` sets it; a cookie for `localhost` reaches every port) and redirects
to the callback with a code. Without the cookie it redirects with `error=login_required`. The double
`backoffice-state` exchanges the code at `POST /sessions`. It checks the PKCE verifier and the redirect
address, and opens a session whose token is an unsigned JWT holding `sub`, `name` and
`realm_access.roles`. The server reads that token and does not verify it. `env.ts` names the operators
the world signs in as: the four default roles of `goldvibe`, `brand-less` (an operator of `spinvibe`
only) and `platform_admin`.

The `__Host-` cookies are `Secure`. Chromium keeps them over plain `http://localhost` because it treats
`localhost` as a secure context, so the browser suites use `localhost` and need no TLS.

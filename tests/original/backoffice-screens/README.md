# The original's operator screens

The *before* for spec 030's design review (T131, research R19): one capture per screen per role of
the original's two operator frontends, `casino/frontend` (the casino backoffice, which also mounts
the payments pages under `/payments/*` and the affiliate programme under `/affiliates/*`) and
`payments/frontend` (the standalone payments backoffice, the same page components in their own
shell). Nothing is replayed from here: each owning service's recordings already hold its routes.
The comparison with the built screens is `specs/030-backoffice/checklists/design-review.md`.

| Path | What it is |
|---|---|
| `record.ts` | the Playwright script: signs in through the realm's form as each persona, sets the working scope to the brand, opens every screen (and each tab of a screen that has them), captures it whole, and writes `index.json` |
| `personas.sh` | makes one operator per recorded role in the original's `eitheror` realm on kind and writes the personas file `record.ts` reads; run by a person (it creates realm users with roles) |
| `index.json` | the index of the last recording: per capture the screen, the original's component, the role, the route it landed on, the outcome (`captured`, `refused`, `not reached` with why) and the image's SHA-256 |
| `captures/` | the images (git-ignored, R19: images stay out of git); `index.json` names each by file and hash |

The script is read-only: it opens screens and presses only tabs and the first record of a list. It
never presses an action, and the payments app's API calls it serves refuse every write.

## The roles

What the original renders depends on the realm roles in the token (`casino/frontend/src/App.tsx`,
`lib/roles.ts`, `PlayerDetailPage.tsx`, `payments/frontend/src/components/RoleGuard.tsx`), so a role
is recorded where it changes what is drawn:

| Role | Why it is its own capture |
|---|---|
| `platform_admin` (with `resource_admin`, as the realm's `dev-operator`) | everything: Team, payments administration (hierarchy, routing rules, PSPs, risk), affiliates, configuration |
| `brand_admin` | configuration, compliance and affiliates, no Team or payments administration; account management on a player |
| `brand_compliance` | responsible gambling, fraud cases and KYC policy; no configuration; the KYC and RG panels on a player |
| `resource_finance` | the withdrawal decision on a player; otherwise the plain operator's screens (`brand_finance` is not a role of the `eitheror` realm) |
| `support_agent` | the plain operator: the casino screens with no compliance, configuration or administration |
| `affiliate_manager` | the Affiliates section only |
| `affiliate` | the wrong-portal page, captured once per app |
| `anyone` (signed out) | the invitation, password-reset and not-authorised pages |

The personas other than `platform_admin` hold the platform's scope (`eitheror_scope_level=platform`,
`eitheror_scope_id=*`), as `dev-operator` does, so the role is the only difference between them; the
script sets the working scope to the brand (`BRAND`, default `goldvibe`) as the scope selector would.

## Running it

Against the original on the kind installation `eitheror-dev` only (`deploy/kind/` of the original;
`/etc/hosts` and the original's Caddy for `*.eitheror.local`). Never against a shared or production
installation, and never with kubectl's default context.

```bash
S=<a scratch directory>
kind get kubeconfig --name eitheror-dev > "$S/kind-eitheror-dev.kubeconfig"

# 1. The personas (a person runs this; it creates realm users)
tests/original/backoffice-screens/personas.sh "$S/personas.json"

# 2. The payments app is off on kind (the release sets frontend.backoffice.enabled: false), so its
#    build is taken from its image and served by the script at https://payments.eitheror.local
mkdir -p "$S/payments-ui"
cid=$(docker create eitheror/payments-backoffice-ui:dev)
docker cp "$cid:/usr/share/nginx/html/backoffice/payments/." "$S/payments-ui/"; docker rm "$cid"

# 3. The recording (needs services/backoffice's npm install for Playwright)
PERSONAS="$S/personas.json" PAYMENTS_UI_DIR="$S/payments-ui" ORIGINAL_COMMIT=<commit or image> \
  node tests/original/backoffice-screens/record.ts
```

`ONLY=<screen id>,…` records some screens; `OUT` puts the captures elsewhere and `INDEX` the index
(a partial run should not overwrite the committed `index.json`). Detail screens are reached by following the first record of
their list; a list with no record leaves the screen `not reached`, with that reason in the index.

What the script works around, and why:

- **The payments app**: its build names Keycloak as `http://localhost:8180`, whose form posts to the
  realm's hostname and loses its cookie there, so the build is served with
  `https://auth.eitheror.local` in its place; the realm's redirects back to the app are turned into
  navigations the browser context serves (Playwright does not route a followed redirect); its `/v1`
  and `/psp-api` go to merchant-service (host 8086), `/graphql` to substrate-graphql (host 8082),
  as its nginx would. It keeps its session only in memory and signs in again on every load, so its
  routes are opened inside the running app.
- **The frame**: both shells scroll inside `main` under a screen-high frame; the capture lets the
  page grow so each image holds the whole screen at 1440 px wide.
- **Slow reads**: a screen is captured once the network is idle and no spinner or skeleton shows
  (up to a minute; the original's player page takes about 40 s on kind).

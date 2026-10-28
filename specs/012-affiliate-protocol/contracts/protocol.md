# The protocol: the tag, the documents, the pseudonym, the page, the webhook

Research R2 to R4, R14, R15. What a network and a brand agree on, independent of ankka; spec 013's
programme issues and publishes exactly this (013 FR-023: "nothing about the tag that 012 does not").
Protocol version 1.

## The tag (`btag`)

JWS compact serialisation, three base64url segments without padding, `header.payload.signature`:

```json
{"alg":"EdDSA","kid":"k-2026-10","typ":"eo-tag"}
{"v":1,"net":"programme","aff":"aff-7f3c","lnk":"lnk-01","cmp":"summer","sub":["s1","s2"],"iat":1761350400}
```

- `alg` is `EdDSA`, the key Ed25519; anything else is `malformed`.
- `net`, `aff`, `lnk`, `kid`: 1 to 64 characters of `[A-Za-z0-9_.-]`; `cmp` optional, 1 to 64; `sub` at
  most five entries of 1 to 64; `iat` seconds since the epoch. `v` is 1.
- The signature is over the ASCII of `header.payload`.
- A tag is at most 1,024 characters, each in `[A-Za-z0-9_.-]`; longer or other characters: `malformed`.
- A value matching `^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$` is a legacy tag, resolved through
  the programme's legacy tag document during the transition and refused `legacy` after it.

Verification order and refusal reasons: research R6.

## The documents a network publishes

Each is JSON over HTTPS, fetched as a public client with no credential, at most 1 MiB, and says how long
it may be cached; a brand refreshes it when that passes and keeps the old one until then.

**Key document** (a network's; the brand's at `GET /v1/casino/brands/{brand}/attribution/keys`):

```json
{"keys":[{"kid":"k-2026-10","kty":"OKP","crv":"Ed25519","x":"<base64url public key>"}],
 "cacheSeconds":3600,
 "transition":{"firstDay":"2026-11-01","endsAt":"2027-11-01","endedOn":null,"endedBy":null,"legacyTagsLast30Days":12}}
```

`transition` is the brand's only (research R16). A key withdrawn is absent from the next document.

**Revocation list**:

```json
{"asOf":"2026-10-25T10:00:00Z","refreshSeconds":300,"links":["lnk-01"],"affiliates":["aff-9"]}
```

**Legacy tag document** (the programme, during the transition):

```json
{"version":3,"cacheSeconds":3600,"tags":{"3f2b…-…":{"affiliate":"aff-7f3c","link":"lnk-01"}}}
```

**Code document** (per brand, 013 FR-023a):

```json
{"brand":"goldvibe","version":12,"at":"2026-10-25T10:00:00Z","cacheSeconds":300,
 "codes":[{"code":"SUMMER50","offer":"offer-3","tag":"<a tag naming the affiliate and the code's link>"}]}
```

A code is matched regardless of case. The `tag` is verified as any tag is before it binds.

## The pseudonym

`base64url(HMAC-SHA256(key, UTF-8(playerId)))` without padding, under the brand's pseudonym key for the
network (32 bytes, never rotated). Different per network and per brand; the same for the player's life.

## A fact

The contract's `affiliates.attribution` families (`contract/schemas/affiliates.attribution.v1.json`),
as JSON. On every carrier the fact id (`ce-id` on the topic, the body's `factId`), the kind (`type`) and
the protocol version are readable without opening the fact. `ggr-daily` gains `beforeBinding`.

## A feed page

`GET /v1/casino/brands/{brand}/attribution/networks/{network}/feed?cursor=&limit=1000`:

```json
{"facts":[{…},{…}],"cursor":"<next>","kid":"b-2026-10","signature":"<base64url Ed25519 over JCS(facts)>"}
```

The signature is over the JCS (RFC 8785) canonical form of the `facts` array under the brand's key
named by `kid`, listed in the brand's key document. The cursor is opaque. The same cursor always
answers the same facts.

## A webhook post

`POST <address>` with the fact as the body and:

```http
Content-Type: application/json
X-Attribution-Protocol: 1
X-Attribution-Fact-Id: deposit/<pseudonym>/<paymentId>
X-Attribution-Kind: deposit
X-Attribution-Signature: sha256=<hex HMAC-SHA256 of the body under the subscription's secret>
```

A `2xx` acknowledges. Anything else is retried with backoff for 24 hours (1, 5, 15, 60 minutes, then
hourly); then the fact is an undelivered fact the operator can resend or discard. A reader discards a
repeat by its fact id.

## The reverse channel

The key document and the revocation list, and for the programme the legacy tag and code documents, are
the whole of what a network may tell a brand. The brand serves a network no route but the feed and the
key document; anything else a network sends is refused (FR-024).

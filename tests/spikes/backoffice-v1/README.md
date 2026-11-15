# V1 probe

Spec 030 rests on one platform assumption (research V1): an owner judges a call from a web-hosted
service by the operator's token the call carries, with no grant made for it. This probe is that
call and nothing else.

`GET /probe/<service>/<path>` calls `$ANKKA_SERVICES_URL/<service>/<path>`, passing on only the
request's `Authorization`, and answers the status, whether the proxy answered
(`X-Ankka-Answered-By: proxy`) and the start of the body.

```bash
just backoffice-spike goldvibe
P=https://backoffice-probe-goldvibe.127.0.0.1.sslip.io:8443/probe
curl -sk "$P/players/v1/casino/backoffice/goldvibe/players?pageSize=1"                          # 401
curl -sk -H "Authorization: Bearer $TOKEN" "$P/players/v1/casino/backoffice/goldvibe/players"  # 200
curl -sk -H "Authorization: Bearer $TOKEN" "$P/merchant.payments/v1/operators"                 # (b)
```

`TOKEN` is an operator's access token at the `eitheror` realm holding `brand_<slug>`. Remove the
probe afterwards: `ankka services delete backoffice-probe --project goldvibe`.

What it answered on 2026-11-15 is in `specs/030-backoffice/research.md` under V1.

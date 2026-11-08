# crm-stub

Customer.io's two hosts in one programme (spec 014 research R2), so the original's CRM clients and
the port's can both run a dispatch with no request leaving the machine. Point the original's
`CUSTOMERIO_TRACK_URL` / `CUSTOMERIO_APP_URL` (the port's `CRM_TRACK_URL` / `CRM_APP_URL`) at it.

| Host | Port | Route | Credentials | Answer |
|---|---|---|---|---|
| Track | 18201 | `POST /api/v2/entity` (`{"type":"person","action":"identify"\|"event"\|"delete","identifiers":{"id"},"name"?,"attributes"}`) | basic `siteId:trackKey` | 200 `{}` |
| App | 18202 | `POST /v1/send/email` | `Authorization: Bearer <appKey>` | 200 `{"delivery_id":"<random>","queued_at":<unix>}` |

Wrong or missing credentials: 401. A body that is not JSON: 400. The body's shape is not checked.

## Running it

```bash
cd tests && go build -o stubs/bin/ ./stubs/crm-stub && stubs/bin/crm-stub
```

| Flag | Variable | Default |
|---|---|---|
| `-track-port` | `CRM_STUB_TRACK_PORT` | `18201` |
| `-app-port` | `CRM_STUB_APP_PORT` | `18202` |
| `-site-id` | `CRM_STUB_SITE_ID` | `stub-site` |
| `-track-key` | `CRM_STUB_TRACK_KEY` | `stub-track-key` |
| `-app-key` | `CRM_STUB_APP_KEY` | `stub-app-key` |
| `-hang` | | `30s`: how long a hang holds before answering 504 |

## Controls (either port; one log and one script for both hosts)

| Route | Body | Answer |
|---|---|---|
| `GET /healthz` | | `{"stub":"crm-track"\|"crm-app","status":"ok"}` |
| `POST /__control` | `{"mode":"429"\|"500"\|"503"\|"hang","count":n}` (`count` defaults to 1) | `{"mode","count","queued"}`; 400 for another mode |
| `POST /__control` | `{"mode":"reset"}` | clears the script and the log |
| `GET /__received` | | every call to either host, oldest first: `{"host":"track"\|"app","at","method","path","headers","body"}` |

The script queues: each of the next *n* calls to either host, in arrival order, takes one step before
its credentials are checked. A status step answers that status; a hang holds the connection until the
client gives up or `-hang` passes. Every call is logged, scripted or refused ones included.

## Not reproduced

- Any other Track or App route (`/api/v1/…`, `/v1/send/push`, campaigns): 404.
- Customer.io's rate limits and payload validation.

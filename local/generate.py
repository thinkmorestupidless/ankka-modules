#!/usr/bin/env python3
"""A brand as one project on this machine, for `ankka local up` (spec 027 T004-T006, T060, T051).

    just local-project guyanabet            # renders the brand, then runs this
    python3 local/generate.py <slug> <plan.json>

Reads the brand's plan (`just modules render <slug> --format json`), the shared `payments` and
`games` manifests, and each service's own `services/<name>/service.json` for its runtime, and writes
`local/<slug>/`: `context-map.json`, `services/<name>/service.json`, `topics.json` and `local.json`.

One machine is one project, so the brand's services and the shared ones it calls sit side by side:
a call to another project resolves by the service's name alone outside a cluster (ankka's
`HttpServiceClients`), so `payments/merchant`'s call to the brand's `wallet` reaches it unchanged.

What differs from the manifests, and only this:
- addresses: the realm at the `identity` companion (http://localhost:18180, issuing as that), the
  site at http://localhost:3310, each service at `host.docker.internal:<port>` from the others;
- every secret is a local-only value, named so; none is a secret anywhere else;
- images are the `0.0.0` builds of this branch (`just local-images`);
- `support-agent` is left out: it needs a model key, which is never written here, or a script file
  (`AGENT_MODEL_PROVIDER=scripted:<file>`), which `ankka local` cannot put in a service's container.
  The site still runs the support module, so its chat stands at `unavailable`;
- `kyc` has no bucket (all six `ANKKA_S3_*` unset), so it runs without documents, which this slice
  does not carry;
- `payouts` and `attribution` are left out: no behaviour of the SDK calls them;
- ST8 is the game provider simulator (spec 020, the shared `tools` project), as a second provider
  configuration `st8` beside the house provider `eitheror`, which has no game yet. So the brand's
  ST8 key is a local value too: nothing here reaches ST8.

- the brand's cashier key is the one this machine's `merchant` issued (`onboard-payments.py`),
  read from `local/<slug>/.ankka/local/merchant-key.env` when it is there, else a placeholder,
  with which a deposit is refused until onboarding has run and this has run again.

The generated files are committed beside this script, but `local.json`, which holds that key and
is ignored; run it again after a manifest changes or after onboarding payments.
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPOSITORY = "europe-west1-docker.pkg.dev/eitheror/casino"
TAG = "0.0.0"
IDENTITY = "http://localhost:18180"
SITE = "http://localhost:3310"

# Each service's port on this machine; the site's is its proxy's.
PORTS = {
    "wallet": 9201, "players": 9202, "responsible-gambling": 9203, "kyc": 9204, "game-provider": 9205,
    "rewards": 9206, "engagement": 9207, "support-agent": 9208,
    "merchant": 9211, "ingest": 9212, "psp-gateway": 9213, "provider": 9214, "game-provider-simulator": 9215,
    "player-site": 3310,
}
LEFT_OUT = {"payouts", "attribution", "support-agent"}
# The payment provider stub's ports (tests/stubs/psp-stub/README.md).
STUB = {"BOUNCEMONEY": 18101, "COZENI": 18102, "DREAMZPAY": 18103, "EUPAYMENTZ": 18104, "GIGADAT": 18105,
        "MORPHOSIS": 18106, "T365": 18107, "UNITYFINANCE": 18108, "DIGITARIUM": 18109}


def at(service: str) -> str:
    return f"http://host.docker.internal:{PORTS[service]}"


def realm_values(realm: str) -> dict:
    """The auth values every ankka service of the brand reads, at the local realm."""
    jwks = "{{companion.identity.url}}"
    return {
        "ANKKA_AUTH_ISSUERS": "player,staff",
        "ANKKA_AUTH_REALM": realm,
        "ANKKA_AUTH_PLAYER_ISSUER": f"{IDENTITY}/realms/{realm}",
        "ANKKA_AUTH_PLAYER_JWKS_URL": f"{jwks}/realms/{realm}/protocol/openid-connect/certs",
        "ANKKA_AUTH_PLAYER_AUDIENCE": realm,
        "ANKKA_AUTH_STAFF_ISSUER": f"{IDENTITY}/realms/eitheror",
        "ANKKA_AUTH_STAFF_JWKS_URL": f"{jwks}/realms/eitheror/protocol/openid-connect/certs",
        "ANKKA_AUTH_STAFF_AUDIENCE": "eitheror-casino",
        "KEYCLOAK_ISSUER_BASE": IDENTITY,
        "KEYCLOAK_JWKS_BASE": jwks,
    }


def local_value(service: str, name: str, value: str, slug: str) -> str | None:
    """A manifest's value as it is on this machine; None leaves the variable out."""
    if value.startswith("secret:") or value.startswith("setting:"):
        key = value.split("/", 1)[-1].split(":", 1)[-1].lower()
        if name == "MODEL_PROVIDER_KEY":
            return "local-only-no-model-key"
        if name == "PROVIDER_LOCAL_CA":
            return None
        if name in ("KEYCLOAK_ADMIN_USERNAME", "KEYCLOAK_ADMIN_PASSWORD"):
            return "admin"  # the identity companion's development administrator
        if name.endswith("_BASE_URL") or name in ("UNITYFINANCE_PAY_URL", "UNITYFINANCE_API_URL") or name.startswith("DIGITARIUM_"):
            provider = name.split("_", 1)[0]
            # DreamzPay's address is posted to by the player's browser, never called by the gateway.
            host = "localhost" if provider == "DREAMZPAY" else "host.docker.internal"
            base = f"http://{host}:{STUB[provider]}"
            return base + "/v1" if name == "UNITYFINANCE_API_URL" else base
        if name == "PUBLIC_URL":
            return f"http://localhost:{PORTS[service]}"
        if name == "PROVIDER_EXTERNAL_URL":
            return f"http://localhost:{PORTS['provider']}"
        if name == "CASINO_REPORTING_ZONE":
            return "America/Cayenne"
        return f"local-only-{key}"
    if name in ("KEYCLOAK_URL",):
        return "{{companion.identity.url}}"
    if name == "PLAYER_SITE_BASE":
        return SITE
    if name == "INGEST_URL":
        return at("ingest")
    if name == "OPERATOR_CONSOLE_URL":
        return f"http://localhost:{PORTS['merchant']}"
    if name == "AGENT_MODEL_PROVIDER":
        return "scripted:"
    if name.startswith("ANKKA_AUTH_") or name.startswith("KEYCLOAK_") and name.endswith("_BASE"):
        return None  # from realm_values
    return value


def main(slug: str, plan_file: str) -> None:
    plan = json.loads(pathlib.Path(plan_file).read_text())
    realm = f"casino-{slug}"
    out = ROOT / "local" / slug
    services: dict[str, dict] = {}   # name -> {"env": {...}, "owns": ..., "web": bool}
    topics: dict[str, dict] = {}

    def topic(t: dict) -> None:
        entry = {"name": t["name"], "partitions": t.get("partitions", 6)}
        retention = t.get("retention")
        if retention:
            entry["retention"] = "everything" if retention in ("fact", "everything") else retention
        old = topics.get(t["name"])
        if old is None or old["partitions"] < entry["partitions"]:
            topics[t["name"]] = entry

    for module in plan["modules"]:
        for t in module["topics"]:
            topic(t)
        for s in module["services"]:
            if s["name"] in LEFT_OUT:
                continue
            services[s["name"]] = {"env": s["env"], "owns": f"the brand's {s['name']} ({module['name']} module)",
                                   "brand": True}

    for project in ("payments", "games", "tools"):
        manifest = json.loads((ROOT / "shared" / project / "manifest.json").read_text())
        for t in manifest.get("topics", []):
            topic(t)
        for s in manifest["services"]:
            env = dict(s.get("env") or {})
            if s["name"] == "merchant":
                env["MERCHANT_BRANDS"] = json.dumps([{"slug": slug, "name": slug}])
            if s["name"] == "game-provider-simulator":
                # ST8 on this machine: posts to <baseAddress>/st8/*, game-provider's callbacks for `st8`.
                env = {"SIMULATOR_BRANDS": json.dumps([{"slug": slug, "baseAddress": at("game-provider"), "testBrand": True}]),
                       "SIMULATOR_EXTERNAL_URL": f"http://localhost:{PORTS['game-provider-simulator']}",
                       "SIMULATOR_RETENTION_DAYS": "7"}
            services[s["name"]] = {"env": env, "owns": f"the shared {project} project's {s['name']}", "brand": False}
    # The house provider (`eitheror`) has no game yet, so the simulator stands in for ST8 as a second
    # configuration, `st8`, which is what the brand's lobby lists and launches here.
    services["game-provider"]["env"] = services["game-provider"]["env"] | {"CASINO_PROVIDERS": "eitheror:st8,st8:st8"}

    missing = sorted(set(services) - set(PORTS))
    if missing:
        sys.exit(f"no local port for {', '.join(missing)}: add it to PORTS")

    # The cashier key this machine's merchant issued, never committed (local/.gitignore).
    issued = out / ".ankka" / "local" / "merchant-key.env"
    merchant_key = next((line.split("=", 1)[1].strip() for line in issued.read_text().splitlines()
                         if line.startswith("MERCHANT_KEY=")), None) if issued.exists() else None

    values: dict[str, dict] = {"*": realm_values(realm)}
    for name, s in services.items():
        own = {}
        for k, v in s["env"].items():
            lv = local_value(name, k, str(v), slug)
            if lv is not None:
                own[k] = lv
        if name == "players" and merchant_key and "PLAYERS_MERCHANT_KEY" in own:
            own["PLAYERS_MERCHANT_KEY"] = merchant_key
        if name == "player-site":
            own["PLAYER_SITE_LOCAL"] = "1"
            own = {k: v for k, v in own.items() if not k.startswith("ANKKA_")}
        values[name] = dict(sorted(own.items()))

    # The descriptors: the image of this branch, the port, the runtime the service was built on.
    for name in services:
        own = json.loads((ROOT / "services" / name / "service.json").read_text())["service"]
        d = {"image": f"{REPOSITORY}/{name}:{TAG}", "port": PORTS[name]}
        if own.get("hosting") == "web":
            d |= {"hosting": "web", "processPort": 8080}
        else:
            d["runtime"] = own["runtime"]
            d["resources"] = {"instanceType": "medium"}
        path = out / "services" / name / "service.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"name": name, "service": d}, indent=2) + "\n")

    context = {
        "project": slug,
        "services": [{"name": n, "owns": s["owns"],
                      "boundary": {"criterion": "ownership", "why": "ankka-modules releases it as its own service"}}
                     for n, s in services.items()],
        "crossings": [],
    }
    (out / "context-map.json").write_text(json.dumps(context, indent=2) + "\n")
    (out / "topics.json").write_text(json.dumps({"topics": sorted(topics.values(), key=lambda t: t["name"])}, indent=2) + "\n")

    local = {
        "values": values,
        "companions": [
            {
                "name": "identity",
                "image": "quay.io/keycloak/keycloak:24.0.5",
                "command": ["start-dev"],
                "env": {
                    "KEYCLOAK_ADMIN": "admin", "KEYCLOAK_ADMIN_PASSWORD": "admin",
                    "KC_DB": "postgres", "KC_DB_URL": "{{database.jdbc}}",
                    "KC_DB_USERNAME": "{{database.user}}", "KC_DB_PASSWORD": "{{database.password}}",
                    "KC_HOSTNAME_URL": IDENTITY, "KC_HOSTNAME_STRICT_BACKCHANNEL": "false", "KC_HTTP_ENABLED": "true",
                },
                "ports": [{"port": 8080, "host": 18180}],
                "ready": {"http": {"port": 8080, "path": "/realms/master"}},
                "database": True,
            },
            {
                "name": "psp-stub",
                "image": f"{REPOSITORY}/psp-stub:{TAG}",
                "env": {"PSP_STUB_GATEWAY_URL": at("psp-gateway")},
                "ports": [{"port": p, "host": p} for p in STUB.values()],
                "ready": {"http": {"port": STUB["DREAMZPAY"], "path": "/"}},
                "neededBy": ["psp-gateway"],
            },
        ],
    }
    (out / "local.json").write_text(json.dumps(local, indent=2) + "\n")
    print(f"local/{slug}: {len(services)} services, {len(topics)} topics")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])

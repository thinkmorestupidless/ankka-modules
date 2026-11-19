#!/usr/bin/env python3
"""Onboard the brand guyanabet at the shared payments services running locally.

After this, a deposit from guyanabet opens a cashier session at `merchant` and is routed to the
DreamzPay stub (tests/stubs/dreamzpay, port 18103): DreamzPay's deposit is a browser redirect to the
gateway's relay page, which posts the player's browser to the provider's checkout page.

What it does, at merchant's operator routes, reading before it writes:

  1. the DreamzPay credential on guyanabet's sub-brand (the stub's memberId, totype and secureKey),
     activated: POST /v1/merchants/{subBrand}/psp-credentials, then .../DREAMZPAY/activate?name=
  2. a DreamzPay VISA terminal on the sub-brand taking deposits in EUR, activated:
     POST /v1/merchants/{subBrand}/gateways/DREAMZPAY/integrations, then .../integrations/{id}/activate
  3. the sub-brand's routing tree: EUR deposits of 500 to 50000 minor units (5.00 to 500.00 EUR) go to
     that terminal; saved as a draft and deployed:
     POST /v1/payments/routing-rules, then /v1/payments/routing-rules/deploy?level=subBrand&entityId=
  4. a merchant key (the cashier key): POST /v1/merchants/{subBrand}/keys. Its secret is printed once
     to stdout as MERCHANT_KEY=<value>. When an active key exists, nothing is issued unless --new-key.

merchant keeps no minimum or maximum on a terminal: the 5.00 to 500.00 EUR bounds are the routing
tree's AMOUNT conditions, and EUR is the tree's CURRENCY condition and the terminal's
`supportedCurrencies` property.

The sub-brand is the one merchant's seed made from MERCHANT_BRANDS:
UUID.nameUUIDFromBytes("seed.sub-brand.guyanabet").

Environment:
  MERCHANT     merchant's base URL (default http://127.0.0.1:9211)
  STAFF_TOKEN  a bearer token from the `eitheror` realm admitted by Routes.paymentsOperator: it must
               carry a role of the payments backoffice (use `platform_admin`) and the claim
               `eitheror_scope_level` = `platform` (or a holding, company or sub_brand level with
               `eitheror_scope_id` naming guyanabet's). A token without the scope claim is refused 403.

Usage: onboard-payments.py [--new-key]
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

SLUG = "guyanabet"
PROVIDER = "DREAMZPAY"
SCHEME = "VISA"
CURRENCY = "EUR"
MIN_MINOR = 500      # 5.00 EUR
MAX_MINOR = 50_000   # 500.00 EUR

CREDENTIAL_NAME = "dreamzpay-stub"
CREDENTIAL_ENVIRONMENT = "sandbox"
TERMINAL_NAME = "dreamzpay-eur"
KEY_NAME = f"{SLUG}-cashier"

# The stub checks the checksum under its secureKey (DREAMZPAY_STUB_SECURE_KEY, default below); it
# accepts any memberId and totype, so these only need to agree between credential and checksum.
STUB_CREDENTIALS = {
    "memberId": f"{SLUG}-member",
    "totype": SLUG,
    "secureKey": os.environ.get("DREAMZPAY_STUB_SECURE_KEY", "dreamzpay-test-key"),
}
TERMINAL_PROPERTIES = {
    **STUB_CREDENTIALS,
    "terminalId": f"{SLUG}-visa",
    "supportedCurrencies": CURRENCY,
}


def name_uuid_from_bytes(data: bytes) -> str:
    """Java's UUID.nameUUIDFromBytes: MD5 of the bytes (no namespace), version 3, IETF variant."""
    b = bytearray(hashlib.md5(data).digest())
    b[6] = (b[6] & 0x0F) | 0x30
    b[8] = (b[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(b)))


class Merchant:
    def __init__(self, base: str, token: str):
        self.base = base.rstrip("/")
        self.token = token

    def call(self, method, path, body=None, ok=(200, 201, 202, 204), allow=()):
        """The status and the decoded body; a status outside ok and allow prints the failure and exits."""
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                status, raw = resp.status, resp.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        except (urllib.error.URLError, OSError) as e:
            fail(f"{method} {path}: no answer from {self.base}: {e}")
        text = raw.decode("utf-8", "replace")
        if status not in ok and status not in allow:
            fail(f"{method} {path}: {status} {text}")
        try:
            decoded = json.loads(text) if text.strip() else None
        except ValueError:
            decoded = text
        return status, decoded


def fail(message: str):
    print(f"onboard-payments: {message}", file=sys.stderr)
    sys.exit(1)


def note(message: str):
    print(f"onboard-payments: {message}", file=sys.stderr)


def q(s: str) -> str:
    return urllib.parse.quote(s, safe="")


def check_sub_brand(m: Merchant, sb: str):
    status, body = m.call("GET", f"/v1/merchants/{sb}", allow=(404,))
    if status == 404:
        fail(f"sub-brand {sb} not found: has merchant's seed run with {SLUG} in MERCHANT_BRANDS?")
    if body.get("brand") != SLUG:
        fail(f"sub-brand {sb} names brand {body.get('brand')!r}, not {SLUG!r}")
    if body.get("status") != "Active":
        fail(f"sub-brand {sb} is {body.get('status')!r}; a cashier session needs it Active")
    note(f"sub-brand {sb} ({SLUG}) is Active")


def ensure_credential(m: Merchant, sb: str):
    base = f"/v1/merchants/{sb}/psp-credentials"
    one = f"{base}/{PROVIDER}?name={q(CREDENTIAL_NAME)}"
    status, body = m.call("GET", one, allow=(404,))
    if status == 404:
        m.call("POST", base, {
            "provider": PROVIDER,
            "name": CREDENTIAL_NAME,
            "environment": CREDENTIAL_ENVIRONMENT,
            "scheme": SCHEME,
            "credentials": STUB_CREDENTIALS,
        })
        note(f"credential {PROVIDER}/{CREDENTIAL_NAME} registered")
    else:
        keys = set(body.get("credentialKeys") or [])
        missing = sorted(set(STUB_CREDENTIALS) - keys)
        if missing:
            m.call("PUT", one, {"credentials": STUB_CREDENTIALS})
            note(f"credential {PROVIDER}/{CREDENTIAL_NAME} existed without {', '.join(missing)}; secrets replaced")
        else:
            note(f"credential {PROVIDER}/{CREDENTIAL_NAME} exists")
    _, body = m.call("POST", f"{base}/{PROVIDER}/activate?name={q(CREDENTIAL_NAME)}")
    note(f"credential {PROVIDER}/{CREDENTIAL_NAME} is {body.get('status') if isinstance(body, dict) else 'active'}")


def terminal_id(sb: str) -> str:
    # merchant's own id for a terminal (MerchantOps.createIntegration), whitespace made underscores.
    return "_".join(f"{sb}_{PROVIDER}_{SCHEME}_{TERMINAL_NAME}".split())


def ensure_terminal(m: Merchant, sb: str) -> str:
    tid = terminal_id(sb)
    path = f"/v1/merchants/{sb}/gateways/integrations/{q(tid)}"
    status, body = m.call("GET", path, allow=(404,))
    if status == 404:
        _, created = m.call("POST", f"/v1/merchants/{sb}/gateways/{PROVIDER}/integrations", {
            "scheme": SCHEME,
            "name": TERMINAL_NAME,
            "regions": [],
            "properties": TERMINAL_PROPERTIES,
            "supportsDeposit": True,
            "supportsWithdrawal": False,
        })
        if created.get("integrationId") != tid:
            fail(f"merchant named the terminal {created.get('integrationId')!r}, expected {tid!r}")
        note(f"terminal {tid} created")
        _, body = m.call("GET", path)
    else:
        note(f"terminal {tid} exists")
        keys = set(body.get("propertyKeys") or [])
        if not set(TERMINAL_PROPERTIES) <= keys:
            body = m.call("PUT", f"{path}/properties", {"properties": TERMINAL_PROPERTIES})[1]
            note(f"terminal {tid} properties set")
        if not body.get("supportsDeposit"):
            body = m.call("PUT", f"{path}/capability", {"supportsDeposit": True})[1]
            note(f"terminal {tid} now takes deposits")
    if body.get("status") != "ACTIVE":
        m.call("POST", f"{path}/activate")
        note(f"terminal {tid} activated")
    else:
        note(f"terminal {tid} is ACTIVE")
    return tid


def desired_tree(tid: str) -> dict:
    return {
        "rootNodeId": "root",
        "nodes": {
            "root": {
                "nodeId": "root",
                "branches": [{
                    "conditions": [
                        {"conditionType": "CURRENCY", "operator": "EQ", "value": CURRENCY},
                        {"conditionType": "AMOUNT", "operator": "GTE", "value": str(MIN_MINOR)},
                        {"conditionType": "AMOUNT", "operator": "LTE", "value": str(MAX_MINOR)},
                    ],
                    "nextNodeId": "eur",
                }],
            },
            "eur": {"nodeId": "eur", "terminals": [tid]},
        },
    }


def normalised(tree):
    """A tree as merchant renders it, nulls dropped, so a live tree compares with the desired one."""
    if not isinstance(tree, dict):
        return None
    nodes = {}
    for key, n in (tree.get("nodes") or {}).items():
        nodes[key] = {k: v for k, v in n.items() if v is not None}
    return {"rootNodeId": tree.get("rootNodeId"), "nodes": nodes}


def ensure_routing(m: Merchant, sb: str, tid: str):
    where = f"level=subBrand&entityId={q(sb)}"
    tree = desired_tree(tid)
    _, state = m.call("GET", f"/v1/payments/routing-rules/state?{where}")
    own_live = state.get("liveVersion") is not None and "resolvedFrom" not in state
    if own_live and normalised(state.get("liveTree")) == normalised(tree):
        note(f"routing tree v{state.get('liveVersion')} for {CURRENCY} is live already")
        return
    m.call("POST", "/v1/payments/routing-rules", {"level": "subBrand", "entityId": sb, "tree": tree})
    _, deployed = m.call("POST", f"/v1/payments/routing-rules/deploy?{where}")
    note(f"routing tree deployed as v{deployed.get('version')}: {CURRENCY} {MIN_MINOR}-{MAX_MINOR} minor units -> {tid}")


def ensure_key(m: Merchant, sb: str, new_key: bool):
    _, body = m.call("GET", f"/v1/merchants/{sb}/keys")
    active = [k for k in (body.get("keys") or []) if k.get("active")]
    if active and not new_key:
        ids = ", ".join(f"{k.get('keyId')} ({k.get('name')})" for k in active)
        note(f"an active merchant key exists: {ids}; its secret is shown only when issued. "
             "Rerun with --new-key to issue another.")
        return
    if active:
        note(f"issuing a further key beside {len(active)} active one(s)")
    _, created = m.call("POST", f"/v1/merchants/{sb}/keys", {"name": KEY_NAME})
    raw = created.get("rawKey") if isinstance(created, dict) else None
    if not raw:
        fail(f"POST /v1/merchants/{sb}/keys: 201 with no rawKey")
    note(f"merchant key {created.get('keyId')} issued")
    print(f"MERCHANT_KEY={raw}")


def main():
    parser = argparse.ArgumentParser(description=f"Onboard {SLUG} at the local shared payments services.")
    parser.add_argument("--new-key", action="store_true", help="issue a merchant key even when an active one exists")
    args = parser.parse_args()

    token = os.environ.get("STAFF_TOKEN", "").strip()
    saved = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".ankka", "local", "staff-token")
    if not token and os.path.exists(saved):
        token = open(saved).read().strip()  # operator-token.sh's
    if not token:
        fail("STAFF_TOKEN is not set (a platform_admin token from the eitheror realm, eitheror_scope_level=platform)")
    m = Merchant(os.environ.get("MERCHANT", "http://127.0.0.1:9211"), token)

    sb = name_uuid_from_bytes(f"seed.sub-brand.{SLUG}".encode("utf-8"))
    check_sub_brand(m, sb)
    ensure_credential(m, sb)
    tid = ensure_terminal(m, sb)
    ensure_routing(m, sb, tid)
    ensure_key(m, sb, args.new_key)


if __name__ == "__main__":
    main()

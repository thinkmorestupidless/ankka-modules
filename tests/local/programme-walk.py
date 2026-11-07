#!/usr/bin/env python3
"""Quickstart §6 against `just programme-run`: one affiliate earns one CPA, end to end.

The local run's staff issuer is `localhost:18180`. This script is that issuer for as long as it runs: a
throwaway RSA key (made under the service's target/, never committed), its JWKS served where the
recipe looks, and tokens minted with the claims ankka reads (`realm_access.roles`, `eitheror_scope_id`).
A local run reads a brand here's facts from an in-memory topic nothing outside the JVM can reach, so
the facts go in through the other door, a hosted brand's signed webhook (contracts/inbound.md).

Stdlib and the `openssl` CLI only. Run `just programme-run` first, then `python3 tests/local/programme-walk.py`.
"""
import base64, hashlib, hmac, http.server, json, os, pathlib, subprocess, sys, threading, time, urllib.error, urllib.request, uuid

PROGRAMME = os.environ.get("PROGRAMME", "http://localhost:9018")
ISSUER    = "https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror"  # the recipe's ANKKA_AUTH_STAFF_ISSUER
AUDIENCE  = "eitheror-casino"
CERTS     = "/realms/eitheror/protocol/openid-connect/certs"
HERE      = pathlib.Path(__file__).resolve().parents[2] / "services" / "affiliate-programme" / "target" / "local-issuer"
KID       = "local-issuer"


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def key() -> pathlib.Path:
    HERE.mkdir(parents=True, exist_ok=True)
    pem = HERE / "key.pem"
    if not pem.exists():
        subprocess.run(["openssl", "genrsa", "-out", str(pem), "2048"], check=True, capture_output=True)
    return pem


def jwks(pem: pathlib.Path) -> dict:
    out = subprocess.run(["openssl", "rsa", "-in", str(pem), "-noout", "-modulus"], check=True, capture_output=True, text=True).stdout
    n = bytes.fromhex(out.strip().split("=", 1)[1])
    return {"keys": [{"kty": "RSA", "use": "sig", "alg": "RS256", "kid": KID, "n": b64(n), "e": "AQAB"}]}


def token(pem: pathlib.Path, subject: str, roles: list[str], **claims) -> str:
    now = int(time.time())
    header  = {"alg": "RS256", "typ": "JWT", "kid": KID}
    payload = {"iss": ISSUER, "aud": AUDIENCE, "sub": subject, "iat": now, "exp": now + 3600, "realm_access": {"roles": roles}, **claims}
    signing = f"{b64(json.dumps(header).encode())}.{b64(json.dumps(payload).encode())}"
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(pem)], input=signing.encode(), check=True, capture_output=True).stdout
    return f"{signing}.{b64(sig)}"


def serve(document: dict) -> None:
    body = json.dumps(document).encode()

    class Certs(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            ok = self.path == CERTS
            self.send_response(200 if ok else 404)
            self.send_header("content-type", "application/json")
            self.end_headers()
            if ok:
                self.wfile.write(body)

        def log_message(self, *_):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 18180), Certs)
    threading.Thread(target=server.serve_forever, daemon=True).start()


def call(method: str, path: str, body=None, bearer=None, headers=None, raw: bytes | None = None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(PROGRAMME + path, data=data, method=method)
    req.add_header("content-type", "application/json")
    if bearer:
        req.add_header("authorization", f"Bearer {bearer}")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode()
            return r.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, text


def step(what: str, status: int, answer, want: int):
    print(f"{'ok ' if status == want else 'BAD'} {status} {what}")
    if status != want:
        print(f"    {answer}")
        sys.exit(1)
    return answer


def fact(kind: str, brand: str, affiliate: str, link: str, pseudonym: str, **fields) -> dict:
    return {"type": kind, "protocol_version": 1, "fact_id": f"{kind}-{uuid.uuid4().hex[:12]}",
            "occurred_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "brand": brand, "network": "programme",
            "affiliate": affiliate, "link": link, "sub_ids": [], "pseudonym": pseudonym, **fields}


def post_fact(brand: str, secret: str, f: dict):
    raw = json.dumps(f).encode()
    signature = "sha256=" + hmac.new(secret.encode(), raw, hashlib.sha256).hexdigest()
    return call("POST", f"/v1/programme/inbound/{brand}", raw=raw, headers={
        "X-Attribution-Protocol": "1", "X-Attribution-Fact-Id": f["fact_id"], "X-Attribution-Kind": "fact",
        "X-Attribution-Signature": signature})


def main():
    pem = key()
    serve(jwks(pem))
    brand = f"local-{uuid.uuid4().hex[:6]}"
    admin   = token(pem, "local-admin", ["platform_admin"])
    manager = token(pem, "local-manager", ["affiliate_manager", f"brand_{brand}"])

    added = step(f"a platform administrator adds hosted brand {brand}, CPA 25.00 EUR", *call("POST", "/v1/programme/brands", {
        "brandId": brand, "origin": "hosted", "name": f"Local {brand}", "reportingZone": "UTC",
        "defaultPlan": {"planName": "Standard", "planType": "cpa", "cpaAmount": 2500, "cpaCurrency": "EUR"}}, admin), 201)
    secret = added["webhookSecret"]

    applied = step("an affiliate applies", *call("POST", f"/v1/affiliates/{brand}/apply",
                   {"email": f"walk-{uuid.uuid4().hex[:8]}@example.com", "name": "Walk Through"}), 201)
    aff = applied["affiliateId"]
    step("the brand's manager approves them on the default plan", *call("POST", f"/v1/affiliates/{brand}/accounts/{aff}/approve", {}, manager), 200)

    affiliate = token(pem, f"kc-{aff}", ["affiliate"], eitheror_scope_id=aff)
    link = step("the affiliate creates a link", *call("POST", f"/v1/my/{aff}/tracking-links?brand={brand}",
                {"campaign": "walk", "landingPath": "/"}, affiliate), 201)["btag"]

    player = f"ps-{uuid.uuid4().hex[:10]}"
    step("the brand posts a registration", *post_fact(brand, secret, fact("registration", brand, aff, link, player, country="GB")), 200)
    step("the brand posts a first deposit of 50.00 EUR", *post_fact(brand, secret, fact("first-deposit", brand, aff, link, player,
         currency="EUR", amount=5000, reference=uuid.uuid4().hex[:8])), 200)

    for _ in range(40):
        status, revenue = call("GET", f"/v1/my/{aff}/revenue?brand={brand}&period={time.strftime('%Y-%m', time.gmtime())}", bearer=affiliate)
        figures = (revenue or {}).get("figures", []) if isinstance(revenue, dict) else []
        cpa = sum(f.get("cpaCommission", 0) for f in figures)
        if status == 200 and cpa:
            break
        time.sleep(0.5)
    step(f"the affiliate reads their revenue: one CPA, {cpa / 100:.2f} EUR", status if cpa == 2500 else 599, revenue, 200)


if __name__ == "__main__":
    main()

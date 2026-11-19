#!/usr/bin/env python3
"""The brand's two game providers, on this machine (spec 027 T060; services/game-provider/README.md,
services/provider/README.md "A brand's entry").

    ./operator-token.sh && python3 onboard-games.py

For each of `st8` (the game provider simulator standing in for ST8) and `eitheror` (the house
provider): a fresh operator key pair, the brand's provider configuration naming the provider's key
document (a brand that takes no real money may), the site's lobby and deposit addresses, and the wallet
address the provider calls back. Then the house provider's entry for the brand, holding the brand's
operator public key as the configuration reads it back, and a catalogue refresh of each.

The token (STAFF_TOKEN, else .ankka/local/staff-token) needs platform_admin and games. Writing again
replaces the configuration with a new key pair, which is how a key is rotated; nothing else changes.
Stdlib and the `openssl` CLI only.
"""
import json, os, pathlib, subprocess, sys, tempfile, urllib.error, urllib.request

HERE = pathlib.Path(__file__).resolve().parent
BRAND = "guyanabet"
GAME_PROVIDER = os.environ.get("GAME_PROVIDER", "http://127.0.0.1:9205")
PROVIDER = os.environ.get("PROVIDER", "http://127.0.0.1:9214")
SITE = "http://localhost:3310"
# Addresses as the services reach one another (local/generate.py).
FROM_SERVICES = {"st8": "http://host.docker.internal:9215", "eitheror": "http://host.docker.internal:9214"}
CALLBACKS = "http://host.docker.internal:9205"


def token() -> str:
    t = os.environ.get("STAFF_TOKEN") or (HERE / ".ankka/local/staff-token").read_text().strip()
    if not t:
        sys.exit("no staff token: run ./operator-token.sh")
    return t


def call(method: str, url: str, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Authorization": f"Bearer {token()}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url.split('?')[0]}: {e.code} {e.read().decode()[:400]}")


def key_pair() -> str:
    """A fresh EC P-256 private key, base64 of its PKCS#8 DER."""
    with tempfile.TemporaryDirectory() as d:
        pem = pathlib.Path(d) / "k.pem"
        # The curve by name: macOS's LibreSSL otherwise writes its parameters out, which the JDK refuses.
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-param_enc", "named_curve", "-noout", "-out", str(pem)],
                       check=True, capture_output=True)
        der = subprocess.run(["openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(pem), "-outform", "DER"],
                             check=True, capture_output=True).stdout
    import base64
    return base64.b64encode(der).decode()


def configure(provider: str) -> dict:
    base = f"{GAME_PROVIDER}/v1/casino/backoffice/{BRAND}/game-provider/providers/{provider}"
    call("PUT", base, {
        "apiUrl": FROM_SERVICES[provider],
        "site": BRAND,
        "operatorPrivateKey": key_pair(),
        "providerKeyDocumentUrl": f"{FROM_SERVICES[provider]}/.well-known/st8-keys",
        "jurisdiction": "KM",  # Anjouan, Comoros (identity.md, 2026-11-07)
        "lobbyUrl": f"{SITE}/",
        "depositUrl": f"{SITE}/?deposit=true",
        "walletUrl": f"{CALLBACKS}/v1/casino/brands/{BRAND}/{provider}",
    })
    view = call("GET", base)
    print(f"{provider}: configured, api {view.get('apiUrl')}", file=sys.stderr)
    return view


def main() -> None:
    configure("st8")
    house = configure("eitheror")
    public = house.get("operatorPublicKey")
    if not public:
        sys.exit("the eitheror configuration read back has no operatorPublicKey")
    call("PUT", f"{PROVIDER}/v1/provider/brands/{BRAND}", {
        "baseAddress": CALLBACKS,
        "site": BRAND,
        "operatorPublicKey": public,
        # The entry allows only es or pt (026 contracts/brand-entry.md); guyanabet is French first.
        "language": "es",
        "testBrand": True,
        "keyDocument": True,
        "realMoney": False,
    })
    print("eitheror: the brand's entry written", file=sys.stderr)
    for provider in ("st8", "eitheror"):
        call("POST", f"{GAME_PROVIDER}/v1/casino/backoffice/{BRAND}/game-provider/providers/{provider}/catalogue/refresh")
        print(f"{provider}: catalogue refresh started", file=sys.stderr)


if __name__ == "__main__":
    main()

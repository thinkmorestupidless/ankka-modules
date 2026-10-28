# The living features, the glossary and the specs that name their scenarios, checked against each other.
features:
    .github/features-check.sh

# Compile the contract and the modules tool.
build:
    sbt compile

# Every unit suite: the contract, the modules tool, the bound features and the Go stubs.
test:
    sbt test
    cd tests && go test ./...

# Regenerate contract/schemas/<topic>.v1.json from the families; CI fails if this leaves a diff.
schemas:
    sbt contract/schemas

# Run the modules tool (`ankka casino`): `just modules validate --all`.
modules *args:
    sbt "modules/run {{args}}"

# Every manifest at a release version into target/release/, images at the version, schemas inlined (spec 021).
release-render version:
    sbt "modules/run release render {{version}} --out target/release"

# The release's publish loop against the fake vendor plugin: one publish per module, no channel moved (SC-004).
release-dry version:
    #!/usr/bin/env bash
    set -euo pipefail
    out="$(mktemp -d)"
    sbt "modules/run release render {{version}} --out $out"
    ANKKA_VENDOR_FAKE_LOG="$out/vendor.log" PATH="$PWD/tests/fakes:$PATH" scripts/release-publish.sh "$out" "{{version}}"
    echo "the fake vendor was called:"; cat "$out/vendor.log"
    if grep -q '^channels' "$out/vendor.log"; then echo "a channel was moved" >&2; exit 1; fi

# Build the payment-provider stubs into tests/stubs/bin/.
stubs:
    cd tests && go build -o stubs/bin/ ./stubs/...

# The integration suites, against the installation ANKKA_URL names (a project is created there).
it:
    sbt modulesIt/test

# Convert the original's wallet handler fixtures into the wallet's JSON case files (once; spec 002 R13).
tl-fixtures original="../eitheror-platform":
    sbt "tlFixtures/run {{original}}/starter-packs/wallet/handlers services/wallet/src/test/resources/fixtures"

# The wallet's suites (spec 002): the resolver tables, the fixtures, the entity, the bound features,
# and the integration suites over Postgres in Docker. Publishes the contract first.
wallet-test:
    sbt contract/publishLocal
    cd services/wallet && sbt test

# The wallet's integration and parity suites only (Docker).
wallet-it:
    cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.it.* io.eitheror.casino.wallet.port.*"

# The wallet's scale and latency suites: 10,000 transactions, SC-003's figures (Docker).
wallet-perf:
    cd services/wallet && WALLET_PERF=1 sbt "testOnly io.eitheror.casino.wallet.it.ScaleItSuite"

# The wallet, locally, as brand goldvibe (`sbt schema && docker compose up -d` first, in services/wallet).
wallet-run brand="goldvibe":
    cd services/wallet && CASINO_BRAND={{brand}} sbt run

# merchant's suites (spec 004; Docker for the integration kit). The contract is published first.
merchant-test:
    sbt contract/publishLocal
    cd services/merchant && sbt test

# merchant's integration and parity suites only (Docker).
merchant-it:
    cd services/merchant && sbt "testOnly io.eitheror.casino.merchant.it.* io.eitheror.casino.merchant.port.*"

# merchant, locally, seeding brand goldvibe (`sbt schema && docker compose up -d` first, in services/merchant).
merchant-run brands='[{"slug":"goldvibe","name":"Goldvibe"}]':
    cd services/merchant && MERCHANT_BRANDS='{{brands}}' sbt run

# Run sbt in one service's own build: `just service game-provider test`. The contract is published first.
service name +args:
    sbt contract/publishLocal
    cd services/{{name}} && sbt {{args}}

# psp-gateway's suites (spec 005; Docker for the integration kit and psp-stub's image). The contract is published first.
psp-gateway-test:
    sbt contract/publishLocal
    cd services/psp-gateway && sbt test

# psp-gateway's integration and parity suites only (Docker).
psp-gateway-it:
    cd services/psp-gateway && sbt "testOnly io.eitheror.casino.pspgateway.it.* io.eitheror.casino.pspgateway.port.*"

# psp-gateway, locally on 9001, every provider at psp-stub (`just psp-stub` first; `sbt schema && docker compose up -d` in services/psp-gateway).
psp-gateway-run:
    cd services/psp-gateway && PUBLIC_URL=http://localhost:9001 ANKKA_HTTP_PORT=9001 ANKKA_DB_PORT=5433 \
      DREAMZPAY_BASE_URL=http://localhost:18103 UNITYFINANCE_PAY_URL=http://localhost:18108 UNITYFINANCE_API_URL=http://localhost:18108/v1 \
      T365_BASE_URL=http://localhost:18107 COZENI_BASE_URL=http://localhost:18102 EUPAYMENTZ_BASE_URL=http://localhost:18104 \
      GIGADAT_BASE_URL=http://localhost:18105 BOUNCEMONEY_BASE_URL=http://localhost:18101 MORPHOSIS_BASE_URL=http://localhost:18106 \
      DIGITARIUM_SANDBOX_URL=http://localhost:18109 DIGITARIUM_PRODUCTION_URL=http://localhost:18109 \
      sbt -Dankka.local-services.merchant=http://localhost:9000 run

# ingest's suites (spec 006; Docker for the integration kit). The contract is published first.
ingest-test:
    sbt contract/publishLocal
    cd services/ingest && sbt test

# ingest's integration, parity and feature suites only (Docker).
ingest-it:
    cd services/ingest && sbt "testOnly io.eitheror.casino.ingest.it.* io.eitheror.casino.ingest.port.* io.eitheror.casino.ingest.features.*"

# ingest, locally on 9002 beside merchant on 9000 (`just merchant-run` first; `sbt schema && docker compose up -d` in services/ingest).
# The operator routes trust dev-kind's eitheror realm, as merchant's §7 run does (its JWKS through a
# port-forward on 18180); the cashier routes need no Keycloak.
ingest-run:
    cd services/ingest && ANKKA_HTTP_PORT=9002 ANKKA_DB_PORT=5434 ANSWER_BOUND=10s \
      ANKKA_AUTH_ISSUERS=staff ANKKA_AUTH_REALM=eitheror ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=http://localhost:18180/realms/eitheror/protocol/openid-connect/certs \
      sbt -Dankka.local-services.merchant=http://127.0.0.1:9000 run

# responsible-gambling's suites (spec 010; Docker for the integration kit). The contract is published first.
rg-test:
    sbt contract/publishLocal
    cd services/responsible-gambling && sbt test

# responsible-gambling's integration, parity and feature suites only (Docker).
rg-it:
    cd services/responsible-gambling && sbt "testOnly io.eitheror.casino.rg.it.* io.eitheror.casino.rg.port.* io.eitheror.casino.rg.features.*"

# responsible-gambling, locally on 9006 as brand goldvibe (`sbt schema && docker compose up -d` first, in
# services/responsible-gambling); players at 9005 for the exclusion sync. Tokens are verified against the
# kind installation's casino Keycloak (the brand realm for players, `eitheror` for operators).
rg-run brand="goldvibe":
    cd services/responsible-gambling && CASINO_BRAND={{brand}} ANKKA_HTTP_PORT=9006 ANKKA_DB_PORT=5436 \
      ANKKA_AUTH_ISSUERS=player,staff ANKKA_AUTH_REALM=casino-{{brand}} \
      ANKKA_AUTH_PLAYER_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}} \
      ANKKA_AUTH_PLAYER_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}}/protocol/openid-connect/certs \
      ANKKA_AUTH_PLAYER_AUDIENCE=casino-{{brand}} ANKKA_AUTH_PLAYER_CA=$HOME/.ankka/local-ca.crt \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
      ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino ANKKA_AUTH_STAFF_CA=$HOME/.ankka/local-ca.crt \
      sbt -Dankka.local-services.players=http://127.0.0.1:9005 run

# players' suites (spec 008; Docker for the integration kit). The contract is published first.
players-test:
    sbt contract/publishLocal
    cd services/players && sbt test

# players' integration, parity and feature suites only (Docker).
players-it:
    cd services/players && sbt "testOnly io.eitheror.casino.players.it.* io.eitheror.casino.players.port.* io.eitheror.casino.players.features.*"

# players' duplicate detection at scale: 10,000 registrations, then a match (Docker).
players-perf:
    cd services/players && PLAYERS_PERF=1 sbt "testOnly io.eitheror.casino.players.it.DuplicatesScaleSuite"

# players, locally on 9003 as brand goldvibe (`sbt schema && docker compose up -d` in services/players
# first: Postgres on 5435 and the brand's Keycloak on 8080). The services it calls are the fakes
# (`sbt "Test/runMain io.eitheror.casino.players.it.fakes"` in services/players): responsible-gambling
# 9011, kyc 9012, engagement 9013, ingest 9014, wallet 9015, ip-api 9016, the vendor's agent 9017
# (FakeRealm on 9010 stands in for Keycloak when KEYCLOAK_URL points at it).
players-run:
    cd services/players && ANKKA_HTTP_PORT=9003 ANKKA_DB_PORT=5435 CASINO_BRAND=goldvibe CASINO_REAL_MONEY=false \
      KEYCLOAK_URL=http://127.0.0.1:8080 PLAYER_SITE_BASE=http://localhost:3000 \
      INGEST_URL=http://127.0.0.1:9014 GEO_URL=http://127.0.0.1:9016 \
      ANKKA_AUTH_ISSUERS=player ANKKA_AUTH_REALM=casino-goldvibe ANKKA_AUTH_PLAYER_AUDIENCE=account \
      ANKKA_AUTH_PLAYER_ISSUER=http://127.0.0.1:8080/realms/casino-goldvibe \
      ANKKA_AUTH_PLAYER_JWKS_URL=http://127.0.0.1:8080/realms/casino-goldvibe/protocol/openid-connect/certs \
      sbt -Dankka.local-services.responsible-gambling=http://127.0.0.1:9011 \
        -Dankka.local-services.kyc=http://127.0.0.1:9012 -Dankka.local-services.engagement=http://127.0.0.1:9013 \
        -Dankka.local-services.wallet=http://127.0.0.1:9015 -Dankka.local-services.merchant=http://127.0.0.1:9015 \
        -Dankka.local-services.vendor-agent=http://127.0.0.1:9017 run

# attribution's unit suites (spec 012). The contract is published first.
attribution-test:
    sbt contract/publishLocal
    cd services/attribution && sbt test

# attribution's integration, parity and feature suites (Docker).
attribution-it:
    cd services/attribution && sbt -Dit=true "testOnly io.eitheror.casino.attribution.it.* io.eitheror.casino.attribution.port.* io.eitheror.casino.attribution.features.*"

# attribution, locally on 9007 (`sbt schema && docker compose up -d` in services/attribution). With no
# ANKKA_KAFKA_BOOTSTRAP_SERVERS the facts are read and published in memory: the click and the operator routes are
# what it shows (quickstart §6). Export ANKKA_SECRET_KEY first (`openssl rand -base64 32`, kept: the store is
# encrypted under it).
# The operator routes trust dev-kind's eitheror realm through a port-forward on 18180, as ingest-run does.
attribution-run:
    cd services/attribution && ANKKA_HTTP_PORT=9007 ANKKA_DB_PORT=5437       CASINO_BRAND=goldvibe CASINO_PROJECT=goldvibe CASINO_REAL_MONEY=false CASINO_REPORTING_ZONE=Africa/Johannesburg       ANKKA_AUTH_ISSUERS=staff ANKKA_AUTH_REALM=eitheror ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino       ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror       ANKKA_AUTH_STAFF_JWKS_URL=http://localhost:18180/realms/eitheror/protocol/openid-connect/certs       sbt run

# The nine provider stubs as one programme (spec 005 FR-025), their webhooks sent to a local psp-gateway.
psp-stub gateway="http://localhost:9001":
    cd tests && go build -o stubs/bin/ ./stubs/psp-stub
    PSP_STUB_GATEWAY_URL={{gateway}} tests/stubs/bin/psp-stub

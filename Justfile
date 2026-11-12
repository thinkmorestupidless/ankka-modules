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
    cd services/wallet && sbt "testOnly io.eitheror.casino.wallet.it.* io.eitheror.casino.wallet.features.*ItSuite io.eitheror.casino.wallet.port.*"

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

# kyc's suites (spec 009; Docker for the integration kit: Postgres and Garage). The contract is published first.
kyc-test:
    sbt contract/publishLocal
    cd services/kyc && sbt test

# kyc's integration, parity and feature suites only (Docker).
kyc-it:
    cd services/kyc && sbt "testOnly io.eitheror.casino.kyc.it.* io.eitheror.casino.kyc.port.* io.eitheror.casino.kyc.features.*"

# kyc, locally on 9012 as brand goldvibe, where players' local run looks for it (`sbt schema && docker
# compose up -d` first, in services/kyc); the bucket and its key from local/bucket.sh; players at 9005 for
# the window's suspend. Tokens are verified against the kind installation's casino Keycloak.
kyc-run brand="goldvibe":
    #!/usr/bin/env bash
    set -euo pipefail
    cd services/kyc
    s3="$(./local/bucket.sh)"   # a failure stops here; inside eval it would start the service with no bucket
    set -a; eval "$s3"; set +a
    CASINO_BRAND={{brand}} ANKKA_HTTP_PORT=9012 ANKKA_DB_PORT=5438 \
      ANKKA_AUTH_ISSUERS=player,staff ANKKA_AUTH_REALM=casino-{{brand}} \
      ANKKA_AUTH_PLAYER_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}} \
      ANKKA_AUTH_PLAYER_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}}/protocol/openid-connect/certs \
      ANKKA_AUTH_PLAYER_AUDIENCE=casino-{{brand}} ANKKA_AUTH_PLAYER_CA=$HOME/.ankka/local-ca.crt \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
      ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino ANKKA_AUTH_STAFF_CA=$HOME/.ankka/local-ca.crt \
      sbt -Dankka.local-services.players=http://127.0.0.1:9005 run

# payouts' suites (spec 007; Docker for the integration kit). The contract is published first.
payouts-test:
    sbt contract/publishLocal
    cd services/payouts && sbt test

# payouts' integration, parity and feature suites only (Docker).
payouts-it:
    cd services/payouts && sbt "testOnly io.eitheror.casino.payouts.it.* io.eitheror.casino.payouts.port.* io.eitheror.casino.payouts.features.*"

# SC-004 at full size: 10,000 requests with 200 in flight (the default run has 1,000; Docker).
payouts-scale:
    cd services/payouts && PAYOUTS_SCALE=1 sbt "testOnly io.eitheror.casino.payouts.it.ScaleSuite"

# payouts, locally on 9004 as brand goldvibe (`sbt schema && docker compose up -d` first, in services/payouts);
# the wallet's local run on 9000 and psp-gateway's on 9001. Tokens are verified against the kind installation's
# casino Keycloak.
payouts-run brand="goldvibe":
    cd services/payouts && CASINO_BRAND={{brand}} ANKKA_HTTP_PORT=9004 ANKKA_DB_PORT=5439 \
      ANKKA_AUTH_ISSUERS=player,staff ANKKA_AUTH_REALM=casino-{{brand}} \
      ANKKA_AUTH_PLAYER_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}} \
      ANKKA_AUTH_PLAYER_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}}/protocol/openid-connect/certs \
      ANKKA_AUTH_PLAYER_AUDIENCE=casino-{{brand}} ANKKA_AUTH_PLAYER_CA=$HOME/.ankka/local-ca.crt \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
      ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino ANKKA_AUTH_STAFF_CA=$HOME/.ankka/local-ca.crt \
      sbt -Dankka.local-services.wallet=http://127.0.0.1:9000 -Dankka.local-services.psp-gateway=http://127.0.0.1:9001 run

# rewards' suites (spec 011; Docker for the integration kit). The contract is published first.
rewards-test:
    sbt contract/publishLocal
    cd services/rewards && sbt test

# rewards' integration, parity and feature suites only (Docker).
rewards-it:
    cd services/rewards && sbt -Dit=true "testOnly io.eitheror.casino.rewards.it.* io.eitheror.casino.rewards.port.* io.eitheror.casino.rewards.features.*"

# rewards, locally on 9008 as brand goldvibe (`sbt schema && docker compose up -d` first, in services/rewards);
# the wallet's local run on 9000, game-provider's on 9009 and players' on 9003. Tokens are verified against
# the kind installation's casino Keycloak.
rewards-run brand="goldvibe":
    cd services/rewards && CASINO_BRAND={{brand}} ANKKA_HTTP_PORT=9008 ANKKA_DB_PORT=5441 \
      ANKKA_AUTH_ISSUERS=player,staff ANKKA_AUTH_REALM=casino-{{brand}} \
      ANKKA_AUTH_PLAYER_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}} \
      ANKKA_AUTH_PLAYER_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}}/protocol/openid-connect/certs \
      ANKKA_AUTH_PLAYER_AUDIENCE=casino-{{brand}} ANKKA_AUTH_PLAYER_CA=$HOME/.ankka/local-ca.crt \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
      ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino ANKKA_AUTH_STAFF_CA=$HOME/.ankka/local-ca.crt \
      sbt -Dankka.local-services.wallet=http://127.0.0.1:9000 -Dankka.local-services.game-provider=http://127.0.0.1:9009 -Dankka.local-services.players=http://127.0.0.1:9003 run

# engagement's suites (spec 014; Docker for the integration kit). The contract is published first.
engagement-test:
    sbt contract/publishLocal
    cd services/engagement && sbt test

# engagement's integration, parity and feature suites only (Docker).
engagement-it:
    cd services/engagement && sbt -Dit=true "testOnly io.eitheror.casino.engagement.it.* io.eitheror.casino.engagement.port.* io.eitheror.casino.engagement.features.*"

# engagement, locally on 9013 as brand goldvibe (`sbt schema && docker compose up -d` first, in services/engagement);
# the CRM is tests/stubs/crm-stub (`go run .` there: Track on 18201, App on 18202); players' local run on 9003 and
# rewards' on 9008. Tokens are verified against the kind installation's casino Keycloak.
engagement-run brand="goldvibe":
    cd services/engagement && CASINO_BRAND={{brand}} ANKKA_HTTP_PORT=9013 ANKKA_DB_PORT=5442 \
      CRM_TRACK_URL=http://127.0.0.1:18201 CRM_APP_URL=http://127.0.0.1:18202 \
      ANKKA_AUTH_ISSUERS=player,staff ANKKA_AUTH_REALM=casino-{{brand}} \
      ANKKA_AUTH_PLAYER_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}} \
      ANKKA_AUTH_PLAYER_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}}/protocol/openid-connect/certs \
      ANKKA_AUTH_PLAYER_AUDIENCE=casino-{{brand}} ANKKA_AUTH_PLAYER_CA=$HOME/.ankka/local-ca.crt \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
      ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino ANKKA_AUTH_STAFF_CA=$HOME/.ankka/local-ca.crt \
      sbt -Dankka.local-services.players=http://127.0.0.1:9003 -Dankka.local-services.rewards=http://127.0.0.1:9008 run

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

# reporting's unit suites (spec 015): the pure packages, the entity, workflow and consumer kits. The contract is
# published first; CASINO_VERSION names a version of this branch's own, as other worktrees publish 0.2.0-SNAPSHOT.
reporting-test:
    sbt contract/publishLocal
    cd services/reporting && sbt test

# reporting's integration, parity and feature suites (Docker), as CI runs them.
reporting-it:
    cd services/reporting && sbt -Dit=true "testOnly io.eitheror.casino.reporting.it.* io.eitheror.casino.reporting.port.* io.eitheror.casino.reporting.features.*"

# reporting at a brand's size, on purpose only: 10,000 players and a month of facts (SC-001, SC-002, SC-003; Docker).
reporting-perf:
    cd services/reporting && REPORTING_PERF=1 sbt -Dit=true "testOnly io.eitheror.casino.reporting.perf.*"

# Records the original's reports, dashboard and dispute report on kind (contracts/recording.md), twice, and merges
# the runs into tests/port/reporting/. The original runs on a local kind cluster only (`kubectl --context kind-…`);
# the operator users and their passwords are the person's (tests/original/README.md, *Recording the reports*).
# The environment carries EITHEROR_OPERATOR_USER/_PASSWORD, EITHEROR_SUPPORT_USER/_PASSWORD,
# WALLET_SERVICE_INTERNAL_SECRET and EITHEROR_UNLEASH_TOKEN; wallet-service's internal API is port-forwarded to 19001.
reporting-record casino="http://localhost:8091" keycloak="http://localhost:8180" brand="spinvibe" fakest8="http://localhost:8096" wallet="http://localhost:19001" unleash="http://localhost:4242" flagoff="reports-off" original="$HOME/Documents/projects/eitheror/eitheror-platform":
    cd tests && for run in 1 2; do rm -rf /tmp/reporting-$run; EITHEROR_RECORD_TO=/tmp/reporting-$run EITHEROR_ORIGINAL_COMMIT=$(git -C {{original}} rev-parse --short HEAD) go test -tags e2e -count=1 -timeout 30m -json ./original/reporting-driver/... -args -casino-url {{casino}} -keycloak-url {{keycloak}} -fake-st8-url {{fakest8}} -wallet-internal-url {{wallet}} -unleash-url {{unleash}} -unleash-environment default -brand {{brand}} -flag-off-brand {{flagoff}} -operator-client casino-backoffice > /tmp/reporting-$run.json; done
    cd tests && go run ./original/merge -run1 /tmp/reporting-1 -run2 /tmp/reporting-2 -results1 /tmp/reporting-1.json -results2 /tmp/reporting-2.json -out port

# reporting's parity tests: each recording of tests/port/reporting compared by shape (Docker).
reporting-parity:
    cd services/reporting && sbt -Dit=true "testOnly io.eitheror.casino.reporting.port.*"

# reporting, locally on 9015 as brand goldvibe (`sbt schema && docker compose up -d` first, in services/reporting).
# With no ANKKA_KAFKA_BOOTSTRAP_SERVERS the facts are read in memory. Tokens are verified against the kind
# installation's casino Keycloak, as rewards-run's.
reporting-run brand="goldvibe":
    cd services/reporting && CASINO_BRAND={{brand}} CASINO_PROJECT={{brand}} CASINO_MODULES=core,casino,reporting \
      CASINO_REPORTING_ZONE=Africa/Johannesburg ANKKA_HTTP_PORT=9015 ANKKA_DB_PORT=5445 \
      ANKKA_AUTH_ISSUERS=player,staff ANKKA_AUTH_REALM=casino-{{brand}} \
      ANKKA_AUTH_PLAYER_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}} \
      ANKKA_AUTH_PLAYER_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/casino-{{brand}}/protocol/openid-connect/certs \
      ANKKA_AUTH_PLAYER_AUDIENCE=casino-{{brand}} ANKKA_AUTH_PLAYER_CA=$HOME/.ankka/local-ca.crt \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror/protocol/openid-connect/certs \
      ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino ANKKA_AUTH_STAFF_CA=$HOME/.ankka/local-ca.crt \
      sbt run

# public-api's unit suites (spec 016): the pure packages, the contract and the kit suites. The contract is published first.
public-api-test:
    sbt contract/publishLocal
    cd services/public-api && sbt test

# public-api's integration, parity and feature suites (Docker), as CI runs them.
public-api-it:
    cd services/public-api && sbt -Dit=true "testOnly io.eitheror.casino.publicapi.it.* io.eitheror.casino.publicapi.port.* io.eitheror.casino.publicapi.features.*"

# public-api's hourly statistics run at a brand's size, on purpose only (SC-008; Docker).
public-api-perf:
    cd services/public-api && PUBLIC_API_PERF=1 sbt -Dit=true "testOnly io.eitheror.casino.publicapi.perf.*"

# Records the original's REST API on kind (specs/016-public-api/contracts/recording.md) into tests/port/public-api/.
# The original runs on a local kind cluster only (`kubectl --context kind-…`); its partner client's secret is the person's.
# The driver writes <EITHEROR_RECORD_TO>/public-api/<name>.json; the original's figures are cut at the hour, so a run
# can wait up to an hour for its statistics (doc.go: -stats-wait, -retention-wait, -write-grants).
public-api-record casino="http://localhost:8091" keycloak="http://localhost:8180" api="http://localhost:8097" original="$HOME/Documents/projects/eitheror/eitheror-platform":
    cd tests && EITHEROR_RECORD_TO=port EITHEROR_ORIGINAL_COMMIT=$(git -C {{original}} rev-parse --short HEAD) go test -tags e2e -count=1 -timeout 90m ./original/public-api-driver/... -args -casino-url {{casino}} -keycloak-url {{keycloak}} -api-url {{api}}

# public-api's parity tests: each recording of tests/port/public-api replayed (Docker).
public-api-parity:
    cd services/public-api && sbt -Dit=true "testOnly io.eitheror.casino.publicapi.port.*"

# public-api, locally on 9016 as brand goldvibe (`sbt schema && docker compose up -d` first, in services/public-api).
# With no ANKKA_KAFKA_BOOTSTRAP_SERVERS the facts are read in memory. Operator tokens are verified against the kind
# installation's casino Keycloak, as reporting-run's.
public-api-run brand="goldvibe":
    cd services/public-api && CASINO_BRAND={{brand}} CASINO_PROJECT={{brand}} ANKKA_HTTP_PORT=9016 ANKKA_DB_PORT=5446 \
      PUBLIC_API_HOST=http://localhost:9016 ANKKA_AUTH_ISSUERS=staff ANKKA_AUTH_REALM=eitheror ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=http://localhost:18180/realms/eitheror/protocol/openid-connect/certs \
      sbt run

# affiliate-programme's unit suites (spec 013). The contract is published first.
programme-test:
    sbt contract/publishLocal
    cd services/affiliate-programme && sbt test

# affiliate-programme's integration and feature suites (Docker).
programme-it:
    cd services/affiliate-programme && sbt -Dit=true "testOnly io.eitheror.casino.programme.it.* io.eitheror.casino.programme.features.*"

# affiliate-programme's parity suites: the recordings of tests/port/programme and attribution's ledger reads (Docker).
programme-parity:
    cd services/affiliate-programme && sbt -Dit=true "testOnly io.eitheror.casino.programme.port.*"

# affiliate-programme, locally on 9018 (`sbt schema && docker compose up -d` in services/affiliate-programme).
# With no ANKKA_KAFKA_BOOTSTRAP_SERVERS goldvibe's affiliates.attribution is carried in memory (quickstart §6).
# The secret store's key is made once in target/local-secret-key and kept: the store is encrypted under it. The
# manager and affiliate routes trust dev-kind's eitheror realm through a port-forward on 18180.
programme-run:
    # The secret store's key for local runs, made once and kept beside the build (never committed).
    cd services/affiliate-programme && mkdir -p target && ([ -s target/local-secret-key ] || openssl rand -base64 32 > target/local-secret-key)
    cd services/affiliate-programme && ANKKA_SECRET_KEY=$(cat target/local-secret-key) ANKKA_HTTP_PORT=9018 ANKKA_DB_PORT=5441       CASINO_PROJECT=programme PROGRAMME_BRANDS=goldvibe       AFFILIATE_PORTAL_BASE_URL=http://localhost:3013 AFFILIATE_BACKOFFICE_BASE_URL=http://localhost:3014 KEYCLOAK_REALM=eitheror       ANKKA_AUTH_ISSUERS=staff ANKKA_AUTH_REALM=eitheror ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino       ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror       ANKKA_AUTH_STAFF_JWKS_URL=http://localhost:18180/realms/eitheror/protocol/openid-connect/certs       sbt run

# the shared audit's suites (spec 018; Docker for the integration kit). The contract is published first.
audit-test:
    sbt contract/publishLocal
    cd services/audit && sbt test

# the shared audit's integration and feature suites (Docker).
audit-it:
    cd services/audit && sbt -Dit=true "testOnly io.eitheror.casino.audit.it.* io.eitheror.casino.audit.features.*"

# the shared audit's parity suites: the recordings of tests/port/audit (Docker).
audit-parity:
    cd services/audit && sbt -Dit=true "testOnly io.eitheror.casino.audit.port.*"

# the shared audit at scale: quickstart §4's ingest, queries and export, and a re-read (Docker; writes PERF.md's figures).
audit-perf:
    cd services/audit && AUDIT_PERF=1 sbt -Dit=true "testOnly io.eitheror.casino.audit.perf.*"

# the shared audit, locally on 9019 (`sbt schema && docker compose up -d` in services/audit). With no
# ANKKA_KAFKA_BOOTSTRAP_SERVERS goldvibe's topics are carried in memory. The secret store's key is made once in
# target/local-secret-key and kept: the registry key is stored under it. Staff tokens are verified against
# dev-kind's eitheror realm through a port-forward on 18180.
audit-run:
    cd services/audit && mkdir -p target && ([ -s target/local-secret-key ] || openssl rand -base64 32 > target/local-secret-key)
    cd services/audit && ANKKA_SECRET_KEY=$(cat target/local-secret-key) ANKKA_HTTP_PORT=9019 ANKKA_DB_PORT=5443 \
      CASINO_PROJECT=reasoner AUDIT_BRANDS=goldvibe KEYCLOAK_REALM=eitheror \
      ANKKA_AUTH_ISSUERS=staff ANKKA_AUTH_REALM=eitheror ANKKA_AUTH_STAFF_AUDIENCE=eitheror-casino \
      ANKKA_AUTH_STAFF_ISSUER=https://auth-casino.127.0.0.1.sslip.io:8443/realms/eitheror \
      ANKKA_AUTH_STAFF_JWKS_URL=http://localhost:18180/realms/eitheror/protocol/openid-connect/certs \
      sbt run

# Quickstart §6 against a running `programme-run`: an affiliate earns one CPA through a hosted brand's webhook.
programme-walk:
    python3 -I tests/local/programme-walk.py

# The nine provider stubs as one programme (spec 005 FR-025), their webhooks sent to a local psp-gateway.
psp-stub gateway="http://localhost:9001":
    cd tests && go build -o stubs/bin/ ./stubs/psp-stub
    PSP_STUB_GATEWAY_URL={{gateway}} tests/stubs/bin/psp-stub

# markets' suites (spec 023; Docker for the integration kit). The contract is published first, at
# `CASINO_VERSION`, and ankka-predictions is resolved at `PREDICTIONS_VERSION`, when the caller sets them.
markets-test:
    just service markets test

# markets' integration suites only (Docker).
markets-it:
    cd services/markets && sbt "testOnly io.eitheror.casino.markets.it.*"

# exchange's suites (spec 023; Docker for the integration kit). The contract is published first, at
# `CASINO_VERSION`, and ankka-predictions is resolved at `PREDICTIONS_VERSION`, when the caller sets them.
exchange-test:
    just service exchange test

# exchange's integration suites only (Docker).
exchange-it:
    cd services/exchange && sbt "testOnly io.eitheror.casino.exchange.it.*"

# exchange's placement latency at 200 orders a second, SC-002's figures (Docker).
exchange-perf:
    cd services/exchange && EXCHANGE_PERF=1 sbt "testOnly io.eitheror.casino.exchange.perf.*"

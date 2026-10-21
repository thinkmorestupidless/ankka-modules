# The living features, the glossary and the specs that name their scenarios, checked against each other.
features:
    .github/features-check.sh

# Compile the contract and the brands tool.
build:
    sbt compile

# Every unit suite: the contract, the brands tool, the bound features and the Go stubs.
test:
    sbt test
    cd tests && go test ./...

# Regenerate contract/schemas/<topic>.v1.json from the families; CI fails if this leaves a diff.
schemas:
    sbt contract/schemas

# Run the brands tool: `just brands validate brands/goldvibe/manifest.json`.
brands *args:
    sbt "brands/run {{args}}"

# Build the payment-provider stubs into tests/stubs/bin/.
stubs:
    cd tests && go build -o stubs/bin/ ./stubs/...

# The integration suites, against the installation ANKKA_URL names (a project is created there).
it:
    sbt brandsIt/test

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

# The nine provider stubs as one programme (spec 005 FR-025), their webhooks sent to a local psp-gateway.
psp-stub gateway="http://localhost:9001":
    cd tests && go build -o stubs/bin/ ./stubs/psp-stub
    PSP_STUB_GATEWAY_URL={{gateway}} tests/stubs/bin/psp-stub

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

# Run sbt in one service's own build: `just service game-provider test`. The contract is published first.
service name +args:
    sbt contract/publishLocal
    cd services/{{name}} && sbt {{args}}

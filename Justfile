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

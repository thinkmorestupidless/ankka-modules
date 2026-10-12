# Recordings of the stand-in's operator API

What the original's `fake-st8` answers to the provider's operator API: launch, the games listing, and
free-round provisioning (create, get, cancel, and their refusals). The simulator's
`OperatorApiParitySuite` replays each against the simulator (spec 020 FR-007, constitution I).

## How they were made

`fake-st8` built from source at the original's commit each file names, run locally on its own (its
adapter and substrate addresses pointed nowhere, which the operator API never calls), and driven
twice on fresh ids. Fields that differed between the two runs are under `vary` with the reason; the
games listing's developers and categories are listed there whether or not they happened to differ,
because the stand-in writes them in Go map order (spec 020 research R7).

The recorder that wrote them is `tests/original/record_operator_api.py`: it posts each request shape twice and writes this
directory's files in spec 001's recording shape (`runs: 2`). `just brands recordings check` accepts them.

The parity of a rebuilt brand's answers with the simulator as its provider is not here: it is spec
003's kit, replaying `tests/port/game-provider/`.

# tests

What holds the rebuild to the original (spec 001, FR-029 to FR-032; `specs/001-brands-and-projects/contracts/recording.md`).

- `port/<service>/` — one recording per scenario of the original's Go end-to-end suite, filed under
  the rebuilt service it establishes requirements for, with `suite-result.json` beside them.
- `stubs/<psp>/` — the payment providers the original calls that had no stub: Go programmes run
  beside the original, each answering `GET /healthz`. Go because they run in the original's toolchain
  beside its own `fake-st8` and `uf-stub`, and nothing on ankka depends on them (constitution VI).
- `original/` — what runs beside the original: the compose override that adds the stubs, and the
  patches to its `tests/e2e` that record and add the missing responsible-gambling and KYC scenarios.
- `fixtures/` — manifests, installations and recordings the Scala suites read.

The Go module is this directory: `go test ./...` from here, or `just test`.

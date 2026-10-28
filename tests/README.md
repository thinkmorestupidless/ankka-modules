# tests

What holds the rebuild to the original (spec 001, FR-029 to FR-032; `specs/001-brands-and-projects/contracts/recording.md`).

- `port/<service>/` — one recording per scenario of the original's Go end-to-end suite, filed under
  the rebuilt service it establishes requirements for, with `suite-result.json` beside them.
- `stubs/<psp>/` — the nine payment providers, one Go package each over `stubs/internal/stubhttp`
  (`GET /healthz`, `POST /__control`, `POST /__webhook`, `GET /__received`), each answering its
  provider's real shapes. `stubs/psp-stub/` serves all nine in one programme and one image,
  `psp-stub`, on ports 18101–18109 (spec 005 FR-025; its `README.md` has the ports and the controls).
  The original's gateway and the port's integration kit are both pointed at it. Unity Finance's stub
  replaces the original's `tests/uf-stub`, which speaks none of the live payout shapes. Go because
  they run in the original's toolchain beside its own `fake-st8`, and nothing on ankka depends on
  them (constitution VI).
- `original/` — what runs beside the original: the compose override that adds `psp-stub` and points
  the gateway's eleven provider base URLs at it, and the patches to its `tests/e2e` that record and
  add the missing responsible-gambling and KYC scenarios; and the drivers that record what the suite
  never reaches, one per ported service (`wallet-driver`, `merchant-driver`, `gateway-driver`,
  `ingest-driver`, `players-driver`), with `merge` and `preflight` (`original/README.md`).
- `fixtures/` — manifests, installations and recordings the Scala suites read.

The Go module is this directory: `go vet ./... && go test ./...` from here, or `just test`. The
image: `docker build -f stubs/psp-stub/Dockerfile -t psp-stub .` from here.

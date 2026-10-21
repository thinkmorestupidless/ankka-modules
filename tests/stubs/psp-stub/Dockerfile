# psp-stub: the nine payment-provider stubs in one image (spec 005 FR-025, research R15).
# Built from tests/ as the context, so the module and stubhttp are in it:
#   cd tests && docker build -f stubs/psp-stub/Dockerfile -t psp-stub .
FROM golang:1.22 AS build
WORKDIR /src
COPY go.mod ./
COPY stubs/ ./stubs/
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /psp-stub ./stubs/psp-stub

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /psp-stub /psp-stub
# BounceMoney, Cozeni, DreamzPay, EuPaymentz, Gigadat, Morphosis, T365, Unity Finance, Digitarium
EXPOSE 18101 18102 18103 18104 18105 18106 18107 18108 18109
ENTRYPOINT ["/psp-stub"]

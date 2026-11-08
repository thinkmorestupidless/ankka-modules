# crm-stub: Customer.io's Track and App hosts in one image (spec 014 research R2).
# Built from tests/ as the context, so the module and stubhttp are in it:
#   cd tests && docker build -f stubs/crm-stub/Dockerfile -t crm-stub .
FROM golang:1.22 AS build
WORKDIR /src
COPY go.mod ./
COPY stubs/ ./stubs/
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /crm-stub ./stubs/crm-stub

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /crm-stub /crm-stub
# Track, App
EXPOSE 18201 18202
ENTRYPOINT ["/crm-stub"]

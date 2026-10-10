# sample-service

A one-endpoint ankka service using every route recipe of `casino-contract` (spec 001 FR-020), for
the access suite to deploy into a brand on a real installation. Not a casino service.

```bash
(cd ../../.. && sbt contract/publishLocal)
sbt compile Docker/publishLocal
kind load docker-image sample-service:latest --name ankka
```

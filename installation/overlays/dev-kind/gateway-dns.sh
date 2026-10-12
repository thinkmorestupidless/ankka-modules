#!/usr/bin/env bash
# Inside the local kind cluster, a gateway hostname (`*.127.0.0.1.sslip.io`) resolves to 127.0.0.1,
# which in a pod is the pod itself. A service that calls another project's public routes as an
# outside caller (the game provider simulator, spec 020 research R3) needs the name to reach the
# gateway instead. This adds one CoreDNS rewrite: every `*.127.0.0.1.sslip.io` name answers with the
# gateway's in-cluster service. Inside the cluster the gateway listens on 443 (8443 is only the host's
# port mapping), so a caller in the cluster names a brand as `https://<service>-<slug>.127.0.0.1.sslip.io`
# with no port, keeping the hostname for SNI and Host, and trusts ~/.ankka/local-ca.crt.
#
# Idempotent; local kind only. Run after ankka's `just up`.
set -euo pipefail

CONTEXT="${KUBE_CONTEXT:-kind-ankka}"
if [[ "$CONTEXT" != kind-* ]]; then
  echo "refusing: $CONTEXT is not a kind cluster; this rewrite is for the local installation only" >&2
  exit 1
fi
k() { kubectl --context "$CONTEXT" "$@"; }

gateway="$(k -n envoy-gateway-system get svc -l gateway.envoyproxy.io/owning-gateway-name=ankka \
  -o jsonpath='{.items[0].metadata.name}')"
if [[ -z "$gateway" ]]; then
  echo "no gateway service found in envoy-gateway-system; is ankka installed?" >&2
  exit 1
fi
target="$gateway.envoy-gateway-system.svc.cluster.local"
rule='    rewrite name regex (.+)\.127\.0\.0\.1\.sslip\.io '"$target"' answer auto'

corefile="$(k -n kube-system get configmap coredns -o jsonpath='{.data.Corefile}')"
if grep -qxF "$rule" <<<"$corefile"; then
  echo "gateway rewrite already present: *.127.0.0.1.sslip.io -> $target"
  exit 0
fi
# a rewrite from an earlier gateway (its name carries a hash) is replaced, not kept beside
corefile="$(grep -v 'rewrite name regex .*sslip.*envoy-gateway-system' <<<"$corefile")"
updated="$(RULE="$rule" awk '{ print } /^\.:53 \{/ && !done { print ENVIRON["RULE"]; done = 1 }' <<<"$corefile")"

k -n kube-system create configmap coredns --from-literal=Corefile="$updated" --dry-run=client -o yaml | k apply -f - >/dev/null
k -n kube-system rollout restart deployment coredns >/dev/null
k -n kube-system rollout status deployment coredns --timeout=120s >/dev/null
echo "gateway rewrite added: *.127.0.0.1.sslip.io -> $target"

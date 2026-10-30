#!/usr/bin/env bash
# Makes the local bucket and a key on docker-compose.yml's Garage, once, and prints them as the
# ANKKA_S3_* the platform would give the service. An existing bucket and key are reused.
set -euo pipefail
admin=http://127.0.0.1:3903
auth="Authorization: Bearer kyc-local"
for _ in $(seq 1 30); do curl -sf "$admin/health" >/dev/null && break; sleep 1; done
bucket=$(curl -sf -H "$auth" "$admin/v2/GetBucketInfo?globalAlias=kyc-local" ||
  curl -sf -H "$auth" -H 'Content-Type: application/json' -d '{"globalAlias":"kyc-local"}' "$admin/v2/CreateBucket")
bucket_id=$(printf '%s' "$bucket" | sed -E 's/.*"id" *: *"([^"]+)".*/\1/' | head -1)
key=$(curl -sf -H "$auth" "$admin/v2/GetKeyInfo?search=kyc-local&showSecretKey=true" ||
  curl -sf -H "$auth" -H 'Content-Type: application/json' -d '{"name":"kyc-local"}' "$admin/v2/CreateKey")
access=$(printf '%s' "$key" | sed -E 's/.*"accessKeyId" *: *"([^"]+)".*/\1/')
secret=$(printf '%s' "$key" | sed -E 's/.*"secretAccessKey" *: *"([^"]+)".*/\1/')
curl -sf -H "$auth" -H 'Content-Type: application/json' \
  -d "{\"bucketId\":\"$bucket_id\",\"accessKeyId\":\"$access\",\"permissions\":{\"read\":true,\"write\":true,\"owner\":true}}" \
  "$admin/v2/AllowBucketKey" >/dev/null
echo "ANKKA_S3_ENDPOINT=http://127.0.0.1:3900"
echo "ANKKA_S3_PUBLIC_ENDPOINT=http://127.0.0.1:3900"
echo "ANKKA_S3_REGION=garage"
echo "ANKKA_S3_BUCKET=kyc-local"
echo "ANKKA_S3_ACCESS_KEY=$access"
echo "ANKKA_S3_SECRET_KEY=$secret"

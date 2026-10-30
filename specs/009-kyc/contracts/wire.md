# Wire translation for parity: kyc

`WireTranslation` turns a request recorded against the original into this service's request, and this
service's answer into the shape recorded (research R2). Each row is one test in `WireTranslationSuite`.

Abbreviations: `<b>` is the brand, `<p>` the player, `<k>` an object key the original returned, `<d>`
the document id this service returned for the same step (captured by the replay as `<k>` was).

| Original request | Here | Answer translated |
|---|---|---|
| `POST /v1/casino/<b>/players/<p>/kyc/upload-url {docType, contentType}` | `POST /v1/casino/kyc/<b>/<p>/uploads {category: docType, contentType}` | `{url, key: objectName, contentType}`; `<d>` captured beside `<k>` |
| `POST /v1/casino/<b>/players/<p>/kyc/documents {key, docType}` | the replay first `PUT`s a small object of the offered type to the signed `url`, then `POST /v1/casino/kyc/<b>/<p>/documents {documentId: <d>}` | `{status: "recorded"}` |
| `GET /v1/casino/<b>/players/<p>/kyc/documents` (player) | `GET /v1/casino/kyc/<b>/<p>/documents` | `{documents: [{key, docType, uploadedAt, reviewStatus, status?, rejectReason?, rejectionReason?, reviewedAt?}], requiredDocTypes, kycStatus}` |
| `GET …/kyc/documents/<k>/view-url` (player) | `GET /v1/casino/kyc/<b>/<p>/views/<d>` | `{url, expiresAt}` |
| `GET …/kyc/documents/<k>/view-url` (operator) | `GET /v1/casino/backoffice/<b>/kyc/<p>/views/<d>` | the same |
| `PATCH …/kyc/documents/<k>/approve` | `POST /v1/casino/backoffice/<b>/kyc/<p>/reviews/<d> {outcome: approved}` | `{docKey: <k>, status: "APPROVED", kycStatus}` |
| `PATCH …/kyc/documents/<k>/reject {reason}` | `POST …/reviews/<d> {outcome: rejected, reasonCode: reason}` | `{docKey: <k>, status: "REJECTED", kycStatus}` |
| `PUT …/kyc/verification-mode {mode}` | `PUT …/<p>/mode {mode}` | `{mode}` |
| `PUT …/kyc/status {status}` | `PUT …/<p>/status {status}` | `{kycStatus: status}` |
| `PUT …/kyc/required-doc-types {docTypes}` | `PUT …/<p>/required {categories: docTypes}` | `{docTypes, verificationMode, kycStatus}` |
| `PATCH …/kyc/verify`, `PATCH …/kyc/reject {reason}` | `POST …/<p>/override {status, reason}` | `{kycStatus}` |
| `POST /v1/casino/backoffice/<b>/<p>/kyc/approve {reason}` | `POST …/<p>/override {status: verified, reason}` | `{playerId, kycStatus, approvedBy, approvedAt, reason}` |
| `GET /v1/casino/backoffice/<b>/kyc-policy` | `GET /v1/casino/backoffice/<b>/kyc/policy` | the policy with `null` fields omitted |
| `PUT …/kyc-policy {…}` | `PUT …/kyc/policy {…}` | the same |
| `POST …/kyc-policy/profile {profile}` | `POST …/kyc/policy/profile {profile}` | the same |
| `GET /v1/casino/players/<b>/kyc/requirements` | `GET /v1/casino/kyc-requirements/<b>` | unchanged |
| `POST /internal/v1/casino/<b>/notify/deposit {playerId, amount, currency}` (the KYC refusals) | `POST /v1/casino/brands/<b>/kyc/deposit-check {playerId, amount: amount × 100, currency}`, as `players` | 403 `{error: code, kycStatus?, message}` from a 200 refusal; 2xx from `allowed` |
| `POST /v1/casino/sessions/<b>/launch` (the KYC refusal) | `GET /v1/casino/brands/<b>/kyc/<p>/play-check`, as `game-provider` | 403 `{error, kycStatus, message}` from a 200 refusal |

**Marked divergences** (`Parity.divergences`):

- an approval's response carries the status after the derivation, where the original carried the
  status before it;
- an unknown document is 404, where the original answered 200;
- `PUT` on the policy reads `CUSTOM`;
- `kycWindowDays` and the thresholds are present as `null`, translated back to omitted;
- the profile-incomplete status is `pending`, where the patch's test waits for `unverified` until it is
  corrected.

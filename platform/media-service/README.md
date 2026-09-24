# media-service

Central media catalogue. Stores binary blobs (receipt photos, voice notes, documents, video)
in an **S3-API object store** (SeaweedFS — see §Object store) and their metadata in
`media.media_object`. Every
domain that ingests media goes through here instead of carrying raw bytes around — finance
receipts first, then the future nutrition / stylist / researcher agents.

Callers reference an object purely by its `id`: upload returns a `MediaObjectDto`, and
`GET /v1/media/{id}` streams the bytes back. The bucket/key layout is an internal detail and
never leaves the service.

## Port: `8088` (`MEDIA_PORT`)

## Endpoints

| method | path                   | purpose                                                         |
|--------|------------------------|-----------------------------------------------------------------|
| POST   | `/v1/media`            | multipart upload (part `file` + form fields); returns `MediaObjectDto` |
| GET    | `/v1/media/{id}`       | raw bytes with the stored content-type                          |
| GET    | `/v1/media/{id}/meta`  | metadata only (`MediaObjectDto` JSON)                           |
| DELETE | `/v1/media/{id}`       | drop the object + row (204; 404 if unknown)                     |
| GET    | `/actuator/health`     | liveness                                                        |

`POST /v1/media` form fields: `householdId` (required), `ownerId` (optional), `kind` (optional —
derived from the MIME type when blank: `image`/`audio`/`video`/`file`), `source` (optional, e.g.
`telegram`). The `file` part's `Content-Type` becomes the stored MIME type. Empty or oversized
uploads → 400.

**No auth** — internal-only by convention (reachable only on the docker network), same posture as
the other platform services. Fetch is by `id` only and is household-agnostic: the caller is assumed
already authorized (orchestrator / agents resolve scope upstream).

## Env

| Var | Default | Purpose |
|---|---|---|
| `MEDIA_PORT` | `8088` | HTTP port. |
| `MEDIA_DB_URL` | `jdbc:postgresql://localhost:5432/ailife` | Postgres (metadata catalogue). |
| `MEDIA_DB_USER` / `MEDIA_DB_PASSWORD` | `ailife` / `ailife` | DB credentials. |
| `S3_ENDPOINT` | `http://localhost:8333` | Object-store S3 endpoint (SeaweedFS S3 API). |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `ailife` / `ailife-secret` | S3 access/secret key (shared with the `seaweedfs` infra service, which enforces them via its identity file). |
| `MEDIA_BUCKET` | `ai-life-media` | Bucket all objects land in (created on startup if absent). |
| `MEDIA_MAX_BYTES` | `10485760` | Hard cap on a single upload (10 MiB). |

## Object store

The store is **SeaweedFS** (Apache-2.0, `chrislusf/seaweedfs`), reached over the S3 API on port
`8333` — decision + alternatives in [ADR-0009](../../plans/adr/ADR-0009-object-store.md). It replaced MinIO on **2026-09-24**, when MinIO's server image stopped being pullable from
any public registry (Docker Hub 401, quay.io 401, ghcr 403) — a fresh CI runner or deploy box could
no longer start the stack at all. The API is the same, so the service code did not change.

Two deliberate details:
- **The client library is still MinIO's (`io.minio`)** even though the server is not. It is a plain
  S3 client, it stayed freely available on Maven Central, and swapping it for the AWS SDK would
  rewrite working code for no gain. The properties are named `media.s3.*` so config does not claim a
  product it no longer uses.
- **Keys are enforced.** `weed server -s3` only checks credentials when handed an identity file, so
  the compose service writes one from `S3_ACCESS_KEY`/`S3_SECRET_KEY` before starting. Anonymous
  access to the store is therefore refused even on the loopback-bound port.

No data migration was needed: nothing is deployed yet (see `plans/STATUS.md` §Deployment reality),
so the old `minio-data` volume held only local scratch.

## Run locally

Bring up the dev infra (Postgres + SeaweedFS) first, then the service:

```sh
docker compose -f infra/docker-compose.dev.yml up -d
mvn -B -pl platform/media-service -am spring-boot:run
```

## Tests

`MediaServiceIntegrationTest` boots the full Spring context with Testcontainers Postgres
(`pgvector/pgvector:pg16`, seeded from `test-schema.sql`) **and** a SeaweedFS container, then drives
the REST surface end-to-end: upload → DB row + byte round-trip through the store → meta → delete →
404, plus the oversized-upload (400) and unknown-id (404) paths. Tests run against the **same store
as the deploy**, so an S3-dialect difference cannot hide until runtime.

## Key classes

- `MediaServiceApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/MediaServiceProperties` — `media.*` (`media.s3.*` endpoint/creds/bucket, max-bytes cap).
  Named after the protocol, not the product — the server behind it has already changed once.
- `config/S3ClientConfig` — builds the singleton S3 client bean.
- `storage/ObjectStore` — single-bucket S3 wrapper (put/get/remove); creates the bucket on
  startup; collapses the SDK's checked-exception surface into one `ObjectStoreException`.
- `domain/MediaRepository` / `domain/MediaRow` — JdbcTemplate over `media.media_object`;
  `MediaRow.toDto()` drops the internal bucket/key.
- `service/MediaService` — store (cap → sha256 → put → insert), fetch, delete; derives `kind`
  from MIME; object key is `<householdId>/<objectId>`.
- `web/MediaController` — the REST surface above.

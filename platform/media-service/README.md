# media-service

Central media catalogue. Stores binary blobs (receipt photos, voice notes, documents, video)
in **MinIO** (S3-compatible object store) and their metadata in `media.media_object`. Every
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
| `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO S3 endpoint. |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | `ailife` / `ailife-secret` | MinIO access/secret key (shared with the `minio` infra service). |
| `MEDIA_BUCKET` | `ai-life-media` | Bucket all objects land in (created on startup if absent). |
| `MEDIA_MAX_BYTES` | `10485760` | Hard cap on a single upload (10 MiB). |

## Run locally

Bring up the dev infra (Postgres + MinIO) first, then the service:

```sh
docker compose -f infra/docker-compose.dev.yml up -d
mvn -B -pl platform/media-service -am spring-boot:run
```

## Tests

`MediaServiceIntegrationTest` boots the full Spring context with Testcontainers Postgres
(`pgvector/pgvector:pg16`, seeded from `test-schema.sql`) **and** a MinIO container, then drives
the REST surface end-to-end: upload → DB row + byte round-trip through MinIO → meta → delete →
404, plus the oversized-upload (400) and unknown-id (404) paths.

## Key classes

- `MediaServiceApplication` — `@SpringBootApplication` + `@ConfigurationPropertiesScan`.
- `config/MediaServiceProperties` — `media.*` (MinIO endpoint/creds/bucket, max-bytes cap).
- `config/MinioConfig` — builds the singleton `MinioClient` bean.
- `storage/ObjectStore` — single-bucket MinIO wrapper (put/get/remove); creates the bucket on
  startup; collapses MinIO's checked-exception surface into one `ObjectStoreException`.
- `domain/MediaRepository` / `domain/MediaRow` — JdbcTemplate over `media.media_object`;
  `MediaRow.toDto()` drops the internal bucket/key.
- `service/MediaService` — store (cap → sha256 → put → insert), fetch, delete; derives `kind`
  from MIME; object key is `<householdId>/<objectId>`.
- `web/MediaController` — the REST surface above.

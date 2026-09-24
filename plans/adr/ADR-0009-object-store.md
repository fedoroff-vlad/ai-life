# ADR-0009: Object store for media blobs — SeaweedFS behind the S3 API

**Status:** Accepted (2026-09-24 — forced by an upstream distribution change, implemented the same day)
**Date:** 2026-09-24
**Deciders:** repo owner (holder/admin)
**Relates to:** [platform.md](../platform.md) §media-service (the only client of this store),
[`platform/media-service/README.md`](../../platform/media-service/README.md) §Object store (the
operational detail), [ADR-0006](ADR-0006-runtime-topology-footprint.md) (the footprint budget a backing
service spends against), [ADR-0007](ADR-0007-authorization-posture.md) (why credentials stay enforced on
a loopback-bound port), [topology-map.md](../topology-map.md) (non-JVM backing tier).

## Context

`media-service` keeps every binary the household produces — receipt and document photos, voice notes,
video, and the rendered HTML boards agents hand back — in an S3-API object store, with only the metadata
in Postgres (`media.media_object`). That store was **MinIO** from Stage 0.

On **2026-09-24** CI went red on a full build: `MediaServiceIntegrationTest` failed with
`ContainerFetchException … quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z` after ~7 minutes of retries,
the registry answering `500 {"message":"unauthorized: access to the requested resource is not
authorized"}`. Probing every registry MinIO has ever published to:

| source | result (2026-09-24) |
|---|---|
| `docker.io/minio/minio` | 401 — repo withdrawn (already known; the test had been moved to quay.io for this reason) |
| `quay.io/minio/minio` | **401 — withdrawn this day**; the previous green main build (2026-09-23) still pulled it |
| `ghcr.io/minio/minio` | 403 |
| `docker.io/bitnami/minio` | 404 — Bitnami moved its catalogue behind a paid plan |

So the MinIO **server image is no longer publicly pullable at all**. Two consequences, one of them worse
than the red build: every full CI run fails, **and a fresh host could not start the stack** — which is
precisely the situation the pending Mac deploy is ([lifecycle.md](../lifecycle.md)). A host with the layer
still in its local cache keeps working, which is exactly why this could go unnoticed until the deploy.

The client library is a separate question from the server: `io.minio` (the MinIO **Java SDK**) is a plain
S3 client and never left Maven Central.

## Decision

**Run SeaweedFS as the object store, reached over the S3 API.** Pinned `chrislusf/seaweedfs:3.97`,
started as `weed server -dir=/data -s3` — one process carrying master + volume + filer + S3, so it stays
**one container**, as MinIO was.

1. **The store is addressed by protocol, not product.** Config is `media.s3.*` / `S3_ENDPOINT`,
   `S3_ACCESS_KEY`, `S3_SECRET_KEY` (was `media.minio.*` / `MINIO_*`). The server behind the S3 API has
   now changed once; naming config after the product is what made this swap touch twenty files instead of
   three.
2. **Keep the `io.minio` SDK as the client.** It speaks S3, it is still freely available, and swapping it
   for the AWS SDK would rewrite working, tested code for no behavioural gain. `S3ClientConfig` (was
   `MinioConfig`) says so in one comment so the next reader does not mistake it for a live MinIO
   dependency. Revisit only if a real S3-dialect incompatibility appears.
3. **Credentials stay enforced.** `weed -s3` authenticates only when handed an identity file, so the
   compose service writes one from `S3_ACCESS_KEY`/`S3_SECRET_KEY` before exec'ing `weed`. Anonymous
   access is refused even though the port is loopback-bound — defence in depth, per ADR-0007, rather than
   leaning on the network boundary alone for the household's photos.
4. **Tests run the same store as the deploy.** Both `MediaServiceIntegrationTest` and
   `PlatformHostFootprintIntegrationTest` (media-service ensures its bucket at boot) start the same
   SeaweedFS image, so an S3-dialect difference cannot hide until runtime. This is a tightening: the
   alternative of a test-only S3 mock was rejected for exactly that reason.
5. **No data migration.** Nothing is deployed yet (STATUS §Deployment reality), so the old `minio-data`
   volume held local scratch only. Had there been live data, the migration would have been a bucket copy
   between two S3 endpoints — the API is the same.

## Alternatives considered

- **Test-only replacement (`adobe/s3mock` or LocalStack S3), leaving MinIO in compose.** The smallest
  change and it would have unblocked CI within the hour. Rejected by the owner: it keeps a store the
  deploy **cannot pull** in the compose file, papering over the real failure and guaranteeing test/prod
  divergence at the worst moment.
- **Garage** (`dxflrs/garage`, also free and publicly pullable). Equivalent on the S3 surface, but it
  needs a `garage.toml` plus a `layout assign` step before S3 answers at all — extra moving parts in both
  compose and Testcontainers. Also AGPL-3.0 vs SeaweedFS's Apache-2.0; no reason to take the stricter
  licence for a backing service. Kept as the fallback if SeaweedFS disappoints.
- **Self-mirror the last MinIO image into our own ghcr.** Preserves the incumbent bit-for-bit, but it
  makes us the distributor of a third party's binary, needs a host that still has the layer cached, and
  inherits a server nobody upstream will publish fixes for. Rejected.
- **Keep MinIO and pay / authenticate to a registry.** Adds a paid dependency and a credential to CI for
  a component whose whole job is holding bytes on one box. Rejected (and the owner's standing preference
  is free, self-hostable components).

## Consequences

- **Positive:** the stack is pullable again on a clean host, CI is green, and the test lane exercises the
  real store. One container, Apache-2.0, actively published. `media-service`'s Java code is unchanged
  (put/get/remove/bucket-ensure), so the blast radius stayed in config + infra.
- **Cost:** a new operational surface to learn (SeaweedFS's master/volume/filer layout shows up in logs
  and in the filer UI on 8888, published only when you uncomment it), and one more `printf`-writes-a-config
  line in compose for the identity file.
- **Watch:** SeaweedFS's S3 coverage is broad but not identical to MinIO's. Today `media-service` uses
  only bucket-exists / make-bucket / put / get / remove, all verified green. Anything richer later
  (presigned URLs, multipart, lifecycle rules, versioning) needs checking against SeaweedFS before it is
  relied on — add it to the IT rather than assuming.
- **Lesson recorded:** a backing service can vanish from distribution without any change on our side. The
  protocol-named config (item 1) is what makes the next such swap cheap; prefer it for every backing
  service we name in config.

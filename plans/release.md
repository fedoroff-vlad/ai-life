# Release & deploy — slices

**Policy source:** [adr/ADR-0011-release-and-deploy.md](adr/ADR-0011-release-and-deploy.md). This file is
the **implementation plan** — the slices, their WHEN/THEN acceptance criteria, and the release checklist
the operator actually follows. The *why* lives in the ADR and is not restated here. Its three forks are
**picked** (owner, 2026-10-29): **RG-A → RG-B staged** (build on the Mac for the first live release, move to
GitHub Actions + GHCR once the ADR-0006 host-units land), **MG-1** forward-only + expand/contract (now a
standing authoring rule in [PATTERNS.md](PATTERNS.md) §Recipe: add a Liquibase migration), and **SR-1**
host-only secrets + an out-of-band copy.

**Procedure source:** the portable skill `tools/agent-skills/skills/release-version/SKILL.md` (submodule)
owns *how to cut a release* — find the last tag, gather the delta, decide the semver bump, update the
version files, generate the changelog, confirm the branch is green, tag, and stop before any push. This
file supplies only the **ai-life bindings** that skill reads (ADR-0011 Decision 4): what the version files
are, what "green" means here, and what happens after the tag. It must never grow a second copy of the
procedure.

**Target:** the Mac Studio deploy described in [lifecycle.md](lifecycle.md). Nothing here is runnable until
that box exists — see [STATUS.md](STATUS.md) §Deployment reality for the `built+green` vs `live` line this
work is what flips.

## What a release is (the short version)

- **One version per repository** — one semver, one git tag `vX.Y.Z`, the same image tag on **every**
  deployable unit of that release. Never per-module.
- **The git tag is the version of record.** The 72 poms stay `0.0.1-SNAPSHOT`; nothing is published to a
  Maven repo. The version reaches runtime as a build arg → image label → env → `/actuator/info`.
- **Semver means operator impact here**, not API compatibility (there are no external consumers):
  **MAJOR** = needs a manual operator step (non-additive migration, embedding re-index, renamed key, model
  re-pull) · **MINOR** = new capability · **PATCH** = fixes/internals. Rule of thumb: if the release notes
  contain an imperative sentence aimed at the operator, it is MAJOR.
- **A merge to `main` is not a release.** The gate has a CI half and a host half (below).
- **The owner deploys, by hand, on the Mac.** `platform/supervisor` owns cold-service lifecycle and is
  **not** the deployer — its socket-proxy grants only `start`/`stop`/`inspect` by design (ADR-0007).

## Release gate — what must be green before a tag

**CI half** (every one of these already exists):
1. `scripts/check-consistency.sh` — all 13 drift checks.
2. gitleaks over the full history.
3. The **full, un-pruned** `mvn verify` on the **main-branch** build — PR CI is GIB-pruned and `-T2`, so a
   green PR check is not evidence the whole reactor is green. This covers the unit/slice tier, the
   `it`-tagged Testcontainers tier and the `E2E…Test` cross-service tier.

**Host half** (none of this can run anywhere but the deploy box):
4. The **golden lane against the deploy model** — `scripts/golden.sh`, the full lane, not the cheap subset
   ([model-strategy.md](model-strategy.md) §Rollout step 3; it is throughput-blocked on the dev VDI).
5. `docker compose --profile hot config` parses as a **valid project** (check 13 guards the *source* of the
   closure invariant, not the rendered project).
6. Every hot unit reaches `/actuator/health` green inside the budget.
7. A **cross-domain smoke** through the real Telegram entry point: calendar + finance + tasks.

Only then: bump, changelog, tag (per the `release-version` skill), deploy.

## Slices

Each slice = one small vertical slice / PR, per CLAUDE.md §Work style. Every scenario below is marked
`not yet asserted` for one of two honest reasons, stated inline: the deploy host does not exist yet (no
Docker daemon, no Mac — the same gate that parks LC-2), or the behaviour is a shell/compose/runbook surface
with no Java test to hang an assertion on. They become real assertions as the slices land.

### RL-1 — versioned image tags: make rollback possible at all
**Requirement:** the stack SHALL run a **named version** of every deployable unit, selected by one env key,
so that moving between versions — forward or back — is a configuration change and not a rebuild.

Today all 50 app services are `image: ai-life/<svc>:local` with a `build:` block, and `start-mac.sh` boots
with `--build`. `:local` means "whatever was last built on this box", which is precisely the thing a
rollback cannot refer to. This slice parameterises the tag and adds `AI_LIFE_IMAGE_TAG` to both env
examples, defaulting to `local` so the dev loop is untouched.

- **Scenario: versioned bring-up**
  - WHEN `AI_LIFE_IMAGE_TAG=v0.1.0` is set and the hot set is brought up
  - THEN every hot container runs the `v0.1.0` image and none resolves to `:local`
    (not yet asserted — needs a Docker daemon + the deploy host)
- **Scenario: dev default is unchanged**
  - WHEN `AI_LIFE_IMAGE_TAG` is unset (the dev loop, `docker-compose.dev.yml`)
  - THEN every service resolves to `:local` exactly as before, and no dev workflow step changes
    (not yet asserted — compose-rendering surface, no Java test; verified by `compose config`)
- **Scenario: rollback by tag**
  - WHEN a release is live on `v0.2.0` and the operator sets the key back to `v0.1.0` and re-runs the
    bring-up
  - THEN the previous images are used as-is with no rebuild and no source checkout
    (not yet asserted — needs a Docker daemon + two built releases)
- **Scenario: a missing tag fails loudly**
  - WHEN `AI_LIFE_IMAGE_TAG` names a tag that was never built or pulled on this host
  - THEN the bring-up fails before any running container is stopped, naming the missing image
    (not yet asserted — needs a Docker daemon; this ordering is the whole point of the scenario)

### RL-2 — version identity: changelog, the skill binding, and the version at runtime
**Requirement:** a running container SHALL be traceable back to its release, and the release SHALL have
exactly one record of what it contains.

- **Scenario: the skill finds its bindings**
  - WHEN the `release-version` skill runs its step 4 ("update version files") in this repo
  - THEN `.skills/change-map.yaml` answers with the ai-life `project-version` coupling — version files:
    *none* (the tag is the version of record); changelog: root `CHANGELOG.md`; green gate: the §Release gate
    above — and the skill's own procedure is used unmodified
    (not yet asserted — the coupling file is data read by an agent, not by a test)
- **Scenario: the running system reports its version**
  - WHEN a container built for `v0.1.0` is asked for its build info
  - THEN it reports `v0.1.0` and the commit it was built from, without a source checkout on the box
    (not yet asserted — needs the build-arg wiring and a running container)
- **Scenario: a release records operator impact**
  - WHEN a release contains a change that will not come up correctly without a manual step (non-additive
    migration · embedding-dimension swap · renamed env key · a model to re-pull)
  - THEN the bump is MAJOR and the changelog entry states the step in the imperative, at the top
    (not yet asserted — authoring discipline; a lint candidate once the changelog format settles)
- **Scenario: one version, not seventy-one**
  - WHEN any module changes in a release
  - THEN the release carries a single repo-wide version and no per-module version is introduced anywhere
    (not yet asserted — structural invariant; a lint candidate: no pom declares its own `<version>`)

### RL-3 — the deploy path: one command, staged images, a health gate, automatic rollback
**Requirement:** deploying a release SHALL be a single operator command that stages **every** unit first,
switches the hot set only after the images are present, and returns the box to the previous version by
itself if the hot set does not come up healthy.

Settled in ADR-0011 item 8 and load-bearing: cold units are staged **before** the switch. A cold unit left
on the old image discovers the new version at its next `supervisor.ensure`, paying a build or a pull at the
worst moment and running a mixed-version stack in the meantime.

- **Scenario: staged before switched**
  - WHEN a release is deployed
  - THEN every unit's image for that version — **cold ones included** — is present on the host before any
    running container is stopped
    (not yet asserted — needs a Docker daemon + the deploy host)
- **Scenario: health gate passes**
  - WHEN the hot set is brought up on the new version
  - THEN the command waits for `/actuator/health` on every hot unit and reports the release complete only
    once all are green inside the budget
    (not yet asserted — needs the running stack; LC-2's health-wait primitive is the same mechanism)
- **Scenario: health gate fails → automatic rollback**
  - WHEN a hot unit does not go healthy inside the budget
  - THEN the command restores the previous version's tag, brings the hot set back up on it, and exits
    non-zero naming the unit that failed — it never leaves the household on a half-started assistant
    (not yet asserted — needs the running stack + an injected failure)
- **Scenario: invalid hot project is caught before anything stops**
  - WHEN the hot profile's dependency closure is broken (the #294 class of failure)
  - THEN `compose config` is validated first and the deploy aborts with nothing stopped
    (not yet asserted — needs a Docker daemon; check 13 guards the source of this invariant in CI)
- **Scenario: the supervisor is not the deployer**
  - WHEN `platform/supervisor` is running
  - THEN it has no path to build, pull, or replace an image — its socket-proxy exposes only
    `start`/`stop`/`inspect` (ADR-0007), and the deploy command is the operator's, not a service's
    (not yet asserted — LC-2 is parked; this is a boundary criterion for it, recorded here so the
    supervisor is not given the wider surface by accident)

### RL-4 — migration safety: dump, tag, and a restore that has actually been run
**Requirement:** a release that migrates the schema SHALL be recoverable, and the recovery path SHALL have
been executed at least once before it is relied on.

The posture is **MG-1** (forward-only + expand/contract) and the authoring rule for it lives in
[PATTERNS.md](PATTERNS.md) §Recipe: add a Liquibase migration — this slice builds the *release-time* half.
The pre-release dump path already exists (`docker exec ai-life-postgres-backup /backup.sh`, [`infra/README.md`](../infra/README.md)
§Database backups) and the off-site `offsite` profile already replicates the dumps.

- **Scenario: dump before migrate**
  - WHEN a release contains any Liquibase changeset not yet applied on the box
  - THEN a fresh dump is taken and confirmed on disk **before** the `liquibase update` runs, and the deploy
    aborts if the dump fails
    (not yet asserted — needs the running stack)
- **Scenario: the release boundary is tagged in the DB**
  - WHEN a release is deployed
  - THEN the changelog carries a `tagDatabase` for that version, so the schema state has a name that
    matches the image tag
    (not yet asserted — needs a Postgres + a release; the changeset itself is authoring discipline)
- **Scenario: rehearsed restore**
  - WHEN the documented restore is executed on the Mac against a throwaway copy
  - THEN it completes, and `infra/README.md` records how long it took and what was lost (the window between
    the dump and the failure)
    (not yet asserted — needs the deploy host; this is the acceptance criterion that turns the backup into
    a rollback plan)
- **Scenario: an image rollback needs no DB rollback**
  - WHEN the schema of release N is in place and the operator rolls the images back to N-1
  - THEN N-1's code reads and writes that schema unchanged, because every changeset of N was additive
    (not yet asserted — needs two releases on the host; this is the expand/contract rule's whole payoff and
    the reason it belongs in `PATTERNS.md` §new migration rather than in a reviewer's memory)

### RL-5 — registry cutover *(RG-B — picked, parked until its gates clear)*
**Requirement:** release images SHALL be built once, from a tagged commit, in a clean environment, and the
deploy box SHALL only pull them.

RG-B is the chosen end state, taken **after** [ADR-0006](adr/ADR-0006-runtime-topology-footprint.md) item 4
collapses the stack from ~50 per-module images to ~13 host-unit images — that is what makes a build-and-push
lane proportionate. Three gates, whichever bites first: host-units landed · the arm64-runner dependency
verified (ADR-0011 Action Item 3) · a full release build measured against the resident model (Action
Item 5). Until then the first releases run RG-A, and this slice is parked rather than open.

- **Scenario: native architecture**
  - WHEN a release image is pulled on the Mac
  - THEN it is a `linux/arm64` image and runs without emulation
    (not yet asserted — needs the registry lane + the deploy host)
- **Scenario: the deploy box does not build**
  - WHEN a release is deployed under RG-B
  - THEN no Maven or Docker build runs on the Mac, and the release spends none of the memory budget the
    resident model needs
    (not yet asserted — needs the registry lane + the deploy host)
- **Scenario: recovery without the source**
  - WHEN the host's image store is empty (fresh disk) and a past version is requested
  - THEN that version is pulled by tag and started, with no rebuild from source
    (not yet asserted — needs the registry lane)
- **Scenario: the registry credential never reaches git**
  - WHEN the pull credential is configured on the host
  - THEN it lives in `infra/secrets/` (already gitignored) and no committed script or compose file contains
    it — gitleaks stays green over the full history
    (not yet asserted — needs the credential to exist; the gitleaks half is already enforced in CI)

### RL-6 — secrets recovery *(SR-1 picked; the SR-2 half stays deferred)*
**Requirement:** the credentials the stack needs SHALL survive the loss of the box, without ever entering
the public repository.

The gap is recorded in ADR-0011 fork SR: off-site replication covers the **DB dumps** and **not**
`infra/.env` or `infra/secrets/`, so today a dead disk loses the Telegram token, the internal shared secret
and the CalDAV credentials, and the stack cannot be rebuilt from the repo alone. **SR-1** closes it with no
machinery — secrets stay on the host, the owner keeps an out-of-band encrypted copy, and the runbook carries
the discipline line. The automatic variant (**SR-2**, `infra/.env` + `infra/secrets/` through an rclone
`crypt` remote on the existing off-site sidecar) is deliberately deferred: take it if the manual copy starts
drifting.

- **Scenario: the backup scope is stated where it is read**
  - WHEN an operator reads the backup documentation
  - THEN it says explicitly that credentials are **not** covered by the off-site replication, and where the
    out-of-band copy lives
    (not yet asserted — documentation criterion)
- **Scenario: rebuild from repo + copy**
  - WHEN the stack is rebuilt on a fresh box from the public repo plus the out-of-band credential copy
  - THEN it boots to a green hot set with no secret recovered from git history
    (not yet asserted — needs a second host; the strongest proof this slice can offer)
- **Scenario: no secret in the repo, ever**
  - WHEN a release slice adds any new configuration key
  - THEN both env examples carry the key with an **empty** value and gitleaks stays green over the full
    history
    (not yet asserted here — already enforced by the CI secret-scan job on every push)

## Out of scope (recorded so it is not re-derived)

- **A staging environment.** One box, one operator, no external consumers — the dev loop plus the release
  gate is the whole promotion path. A second environment would double the config surface for a household
  system.
- **Blue/green or zero-downtime releases.** A release restarts the hot set; `libs/inbox`
  (persist-before-process, #633) and Postgres-held dialog state make that safe, and "seamlessness is not
  required" is already the owner's standing position for the model swap (lifecycle.md §B).
- **Deploy-on-merge / scheduled deploys / remote triggers.** ADR-0011 Decision 7.
- **Per-module versions and a Maven-repo publish.** ADR-0011 Decision 1/2.
- **CI workflow changes.** Only fork RG-B needs one; it is named as an Action Item in the ADR and is not
  part of RL-1…RL-4.

# ADR-0011: Release policy + the dev→prod deploy path for the Mac deploy

**Status:** Proposed (2026-10-28 — awaiting owner picks on the three forks below: **RG** image build
location + registry, **MG** migration-rollback posture, **SR** secrets recovery). Everything outside those
three forks is settled here. Flagged per CLAUDE.md "new layer / pattern → flag before code": release
machinery is a new layer, and the Mac is days away, so this is written **before** any script exists.
**Date:** 2026-10-28
**Deciders:** repo owner (holder/admin)
**Relates to:** [release.md](../release.md) (the slices that implement this — WHEN/THEN there, policy here),
[lifecycle.md](../lifecycle.md) (the deploy target + hot/cold + the `supervisor` this ADR deliberately keeps
out of the deploy path), [ADR-0006](ADR-0006-runtime-topology-footprint.md) (the host-units that become the
release unit, and the `-exec` classifier that already landed for them),
[topology-map.md](../topology-map.md) (which deployable units exist),
[ADR-0007](ADR-0007-authorization-posture.md) (why the supervisor's Docker surface is narrow, and where a
registry credential would sit), [model-strategy.md](../model-strategy.md) (the golden lane that forms the
host half of the release gate), [`infra/README.md`](../../infra/README.md) (backups/restore — the rollback
escape hatch), and the portable `tools/agent-skills/skills/release-version/SKILL.md` (the *procedure*; this
ADR and `release.md` supply only the ai-life bindings).

## Context

The Mac Studio arrives in days and the system gets a **production instance for the first time**. Per
[STATUS.md](../STATUS.md) §Deployment reality everything is `built+green` and **nothing is `live`**; the
lifecycle slices are what flip that. What is missing is not a host — it is the answer to "**what is a
release, and how does one reach the box**". Today there is no answer at all:

| fact (verified 2026-10-28) | consequence |
|---|---|
| All **72** `pom.xml` files carry the literal `0.0.1-SNAPSHOT`; no module declares its own version; internal deps use `${project.version}` | the repo already behaves as **one versioned unit**; a per-module semver would have to be *introduced*, not preserved |
| **Zero git tags** in the repo | there is no "last release" for anything to diff against |
| **50** app services in `infra/docker-compose.yml`, every one `image: ai-life/<svc>:local` + a `build:` block | the image tag carries **no version**; `:local` cannot be rolled back to, because it only ever means "whatever was last built here" |
| `scripts/start-mac.sh` runs `docker compose --profile hot up -d --build` | "deploy" today **is** a source build on the deploy box, from whatever the working tree happens to contain |
| **42** Liquibase feature changelogs, **zero** `rollback:` blocks, **zero** `tagDatabase` | there is no Liquibase-side rollback today; the only DB escape hatch is a dump restore |
| Daily `pg_dump` + rotation, plus an opt-in `offsite` rclone profile (Yandex Disk / Tailscale peer) | the **DB** has off-site replication; `infra/.env` and `infra/secrets/` have **none** |
| `.gitignore` covers `.env*` (minus `*.example`) and `infra/secrets/*`; gitleaks runs on every push over the full history | "secrets stay out of git" is already machine-enforced — the gap is **recovery**, not leakage |
| ADR-0006 slice 3 shipped the `-exec` classifier + 9 `deploy/*-host` launcher modules, but **no `Dockerfile` and no compose service** for any of them | the set of deployable units is **about to change** from ~50 to ~13; a tag scheme bound to modules would be obsolete on arrival |

### Forces / constraints
- **One box, one operator, no external consumers.** Nothing outside the household consumes an ai-life
  artifact. "Breaking change" therefore has no API-consumer meaning here — the only party a release can
  break is the **operator** (the owner) and the **household**.
- **Tooling simplicity + free-first** ([[feedback_tooling_simplicity]]). A release pipeline must be simple
  to run, configure and maintain. A registry, a signing step, a staging environment and a promotion tool
  are all plausible and all machinery a single-box household system has to justify.
- **The 64 GB budget is tight** ([lifecycle.md](../lifecycle.md) §Why): peak ≈ 50 GB with the model
  resident. A Maven + 50-image Docker build **on the deploy box** competes for exactly the RAM and CPU the
  model needs. Building on the host is not free just because it needs no registry.
- **Apple Silicon is arm64; GitHub's default hosted runners are x86_64.** Images built in CI on the default
  runner are `linux/amd64` and would run under emulation on the Mac — slower and fatter, on the one budget
  that cannot absorb it. Any build-in-CI option must produce **native `linux/arm64`**.
- **The repo is public.** Anything committed is public forever (`refs/pull/<N>/head` is served
  indefinitely — the lesson recorded in CLAUDE.md §`scrub-identity`). That rules out "encrypted secrets in
  git" and makes registry/package visibility an explicit choice rather than an afterthought.
- **The supervisor's Docker surface is deliberately narrow** ([lifecycle.md](../lifecycle.md) Decision 1,
  [ADR-0007](ADR-0007-authorization-posture.md)): a `tecnativa/docker-socket-proxy` sidecar exposes only
  `start`/`stop`/`inspect`. A deploy needs `build`/`pull`/`up`/image management — a much wider surface.
- **A release restarts the household's assistant.** Inbound messages survive it (`libs/inbox`
  persist-before-process, #633) and dialog state is in Postgres (`conversation-service`), so a restart is
  *tolerable* — but it is not invisible, and it must not happen on every merge to `main`.
- **PR CI is incremental on purpose** (GIB-pruned, `-T2`); the **main-branch build is the full one**. So a
  green PR check is *not* evidence that the whole reactor is green — which matters when deciding what
  "ready for prod" means.
- **The strongest gate cannot run in CI.** The golden lane needs a real model on real hardware; the dev VDI
  is CPU-only (~7.4 tok/s) and GitHub runners have no model at all. Part of the release gate is therefore
  **host-side by nature** ([model-strategy.md](../model-strategy.md) §Rollout step 3).

## Decision

### Settled (not forked)

1. **One version per repository.** A release is a state of the whole monorepo: one semver, one git tag, one
   image tag applied to **every** deployable unit in that release. Per-module semver is rejected — nothing
   consumes a module independently, the 72 poms are already a single inherited version, and 71 changelogs
   would be 71 drift sources for a system that is deployed as one compose project to one box.
2. **The git tag is the version of record**, and the poms stay `0.0.1-SNAPSHOT`. Nothing is published to a
   Maven repository, so the pom version is inert metadata; a release that must rewrite 72 files is a release
   nobody cuts. The version reaches *runtime* as a build arg → image label → `AI_LIFE_VERSION` env →
   `/actuator/info`, so a running container can be traced back to its commit. (Alternative recorded in the
   RG table: Maven CI-friendly `${revision}` + `flatten-maven-plugin`, if a published artifact ever appears.)
3. **Semver is reinterpreted for a single-operator system**, since "breaks a consumer" has no meaning here:
   - **MAJOR** — the release **needs a manual operator step** and will not come up correctly without it: a
     non-additive migration, an embedding-dimension swap and re-index, a renamed env key that cannot
     self-default, a model re-pull. These are exactly the releases that must not be applied half-asleep.
   - **MINOR** — new capability: a new agent, domain, MCP, skill, or a new user-visible behaviour.
   - **PATCH** — fixes and internals only; applying it is a no-brainer.
   The usefulness is the operator signal, so the rule is: **if the release notes contain an imperative
   sentence aimed at the operator, it is MAJOR.**
4. **The `release-version` skill stays the single source of the *procedure*; ai-life supplies only the
   bindings.** The skill (find last tag → gather delta → decide bump → update version files → changelog →
   green gate → tag, stopping before any push) is portable and is **not edited by this work**. Its step 4
   already says to consult `.skills/change-map.yaml` for a project-version coupling — so the binding lands
   there as a new `project-version` coupling (version files = *none*, the tag + `CHANGELOG.md`; green gate =
   this ADR's definition; artifacts that ride along = §Change propagation). `release.md` describes the
   ai-life-specific steps and **links** to the skill for the procedure; it must not restate it. Two sources
   of truth for "how to cut a release" is precisely the drift CLAUDE.md §Change-propagation forbids.
5. **The release unit is the deployable unit, never the Maven module.** Today that is the ~50 per-module
   images; after [ADR-0006](ADR-0006-runtime-topology-footprint.md) item 4 it is the ~13 host-units. The
   same version tag goes on all of them, so the count can change without the release policy changing. A
   corollary for ADR-0006: its rollout item now also owns **a `Dockerfile` + a compose service per
   `deploy/*-host`** — a launcher module that cannot be packaged is not yet a deployable unit
   (`deploy/platform-host/README.md` already lists this as "not yet done").
6. **"Ready for prod" has a CI half and a host half, and a merge to `main` is neither.** A release candidate
   is a commit on `main` that has passed **all** of:
   - *CI half:* `scripts/check-consistency.sh` (13 checks) · gitleaks · the **full, un-pruned** `mvn verify`
     on the main-branch build (not a GIB-pruned PR run) — which already carries the unit/slice tier, the
     `it`-tagged Testcontainers tier and the `E2E…Test` cross-service tier.
   - *Host half (on the Mac, because it cannot run anywhere else):* the **golden lane against the deploy
     model** · `docker compose --profile hot config` parsing as a valid project (check 13 guards the source
     of that, not the rendered project) · the hot set reaching `/actuator/health` green within a budget ·
     a **cross-domain smoke** (calendar + finance + tasks through the real Telegram entry point).
   Only then is the tag cut. This is the line that makes a release something other than "main moved".
7. **The owner deploys, by one command, on the Mac — and the supervisor is never the deployer.** No
   deploy-on-merge, no scheduled deploy, no remote trigger. Two independent reasons: a release restarts the
   household's assistant, and `platform/supervisor` only has `start`/`stop`/`inspect` through the
   socket-proxy *by design* ([ADR-0007](ADR-0007-authorization-posture.md)) — making it the deployer would
   hand the one network-reachable component the authority to build, pull and replace images. The supervisor
   owns **cold-service lifecycle**; the release script owns **versions**. Separate concerns, separate
   privileges.
8. **A release stages *every* unit — cold ones included — before switching anything.** If a cold host-unit
   is still on the old image when the release flips, its next `supervisor.ensure` pays a build or a pull at
   the worst possible moment, destroying LC-3's instant-cold-start promise and silently running a
   mixed-version stack. So: build/pull all units → verify all images present → *then* restart the hot set.
   A release never leaves a cold unit to discover the new version on its own.
9. **Migrations run before the new code, and the dump is taken before the migration.** The compose
   `liquibase` service already runs `update` as a hot-profile one-shot gated on `postgres` healthy.
   Additionally: a **mandatory pre-release dump** (`docker exec ai-life-postgres-backup /backup.sh` — the
   documented "before a risky migration" path) and a **`tagDatabase` changeset at each release boundary**,
   which is cheap and is the only thing that makes any Liquibase-side rollback or audit possible later.
   **A restore that has never been rehearsed is not a rollback plan** — rehearsing it once on the Mac, with
   the result written down, is an acceptance criterion of the first release, not a nice-to-have.
10. **Config stays where it is; the release adds exactly one key.** `infra/.env.example` remains the SSOT of
    the ~200 defaults, `infra/.env.mac.example` remains the SSOT of the Mac delta **and of the model tags**
    (checks 1–3 of the lint read it), and neither ever carries a real value. The release machinery adds
    `AI_LIFE_IMAGE_TAG` (plus registry keys only if fork **RG** goes that way) to both, and the compose
    `image:` lines become `ai-life/<unit>:${AI_LIFE_IMAGE_TAG:-local}` — so **rollback is an env edit plus
    `up -d`**, and `:local` stays the dev default. Real secrets stay on the host in `infra/.env` +
    `infra/secrets/`, both already gitignored and already watched by gitleaks.

### Fork RG — where images are built, and which registry *(owner's call)*

This is the one choice with real cost on both sides, and it interacts with ADR-0006: building and pushing
**50** images per release is a very different proposition from **13**.

- **Option RG-A — build on the Mac, no registry** (status quo, made releasable). `docker compose build` on
  the host, images tagged `ai-life/<unit>:vX.Y.Z` *and* `:local`; rollback = point `AI_LIFE_IMAGE_TAG` at
  the previous tag, which is still in the host's image store. Native arm64, no new credential, works
  offline. But the image is not a reproducible artifact (it is whatever the working tree held), the build
  competes with the resident model for the tight 64 GB budget, and a wiped disk means rebuilding from
  source rather than pulling a known-good release.
- **Option RG-B — build in GitHub Actions on `linux/arm64`, publish to GHCR** (the end state). The image
  becomes an immutable, versioned, pullable artifact built from a tagged commit in a clean environment; the
  Mac only *pulls*, spending no RAM or CPU on building; and disaster recovery is `docker pull` rather than a
  rebuild. Actions minutes and GHCR storage are free for a public repo. Costs: a release workflow (CI
  change — out of scope here, named as an Action Item), a GHCR read credential on the host (a new file in
  `infra/secrets/`), an explicit package-visibility decision, and a **dependency to verify at
  implementation time: free hosted `linux/arm64` runners for public repositories** — if that does not hold,
  the fallback is buildx + QEMU emulation, which is slow enough for 50 Java images to be a real objection
  and tolerable for 13.
- **Option RG-C — a local registry container on the Mac.** All the registry mechanics (immutable tags,
  `pull`, rollback by tag) with no external dependency and no credential leaving the box. But the artifacts
  still live on the one disk whose failure is the scenario a registry would otherwise cover, and it adds a
  container plus a volume to the stack it is meant to simplify. Strictly worse than RG-A (more machinery,
  same single-point-of-failure) unless a second host appears.

**Recommendation — staged, as ADR-0006 staged B-then-C: take RG-A for the first live release, migrate to
RG-B when the host-units land.** RG-A is the only option that does not block the Mac's arrival on new CI
work, and versioned local tags already buy the thing `:local` cannot do — rolling back. Once ADR-0006 item 4
collapses the stack to ~13 host-unit images, RG-B's costs drop by a factor of four while its wins
(reproducibility, no build next to the model, pull-based recovery) stay — and at that point it should be
taken deliberately, not drifted into.

### Fork MG — migration rollback posture *(owner's call)*

Rolling an image back is easy once tags are versioned (settled item 10). The DB is the hard half, and today
there is nothing: 42 changelogs, no `rollback:` block anywhere.

- **Option MG-1 — forward-only + expand/contract, restore-from-dump as the escape hatch** (recommended).
  The rule becomes: **every changeset must leave the schema readable and writable by the *previous*
  release's code.** Additive change now (nullable column + backfill), destructive change in a *later*
  release once no running code needs the old shape. Then an image rollback needs **no** DB rollback at all,
  which is the only rollback that has to work under pressure. A genuinely bad migration is answered by the
  pre-release dump + a rehearsed restore. Cost: a destructive change takes two releases, and a restore
  loses anything written since the dump.
- **Option MG-2 — mandatory reversible changesets.** A `rollback:` on every changeset plus a tested
  `rollbackToTag`. Gives a one-command DB rollback. But it is a tax on every migration PR plus a rollback
  test lane, and it offers **false confidence exactly where it matters**: Liquibase can un-create a table,
  not un-delete the rows a destructive migration dropped. Under MG-1 those cases don't arise; under MG-2
  they arise and look handled.
- **Option MG-3 — hybrid: forward-only by default, `rollback:` mandatory only on destructive changesets.**
  Sounds like the best of both, and is nearly a no-op: under strict expand/contract a destructive changeset
  only appears in the *contract* release, by which time the code that needed the old shape is already gone —
  and a `rollback:` cannot restore the data anyway. Worth stating so it is rejected on purpose.

**Recommendation: MG-1**, with the pre-release dump and the `tagDatabase` boundary of settled item 9 as
non-negotiable companions, and **expand/contract written into the migration recipe** in
[PATTERNS.md](../PATTERNS.md) so it is applied when the changeset is authored rather than discovered at
rollback time. It is also the only option whose discipline is checkable later by a lint (a changeset that
drops or narrows a column in the same release that stops using it).

### Fork SR — where the real prod secrets live, and how they survive the box *(owner's call)*

There are only a handful of real secrets: `GATEWAY_TELEGRAM_BOT_TOKEN`, `INTERNAL_SHARED_SECRET`,
`CALDAV_USER`/`CALDAV_PASSWORD`, `infra/secrets/rclone.conf` (+ any SSH key), optional capability API keys,
and — under RG-B — a GHCR credential. Keeping them out of git is already solved and machine-enforced. The
open question is **recovery**: the off-site replication covers the DB dumps and **not** `infra/.env` or
`infra/secrets/`, so today a dead disk loses the tokens and the stack cannot be rebuilt from the repo alone.

- **Option SR-1 — host-only + a manual encrypted copy in the owner's password manager** (recommended now).
  Zero machinery, no new dependency, and it fits the real risk: ~6 values that change rarely. The
  discipline is a line in the runbook — "after changing a secret, update the password-manager copy".
- **Option SR-2 — add `infra/.env` + `infra/secrets/` to the off-site sync through an rclone `crypt`
  remote.** Reuses the sidecar that already exists; makes recovery automatic. But it puts the secrets, even
  encrypted, on a third-party disk, and the crypt passphrase then becomes the one secret that must live
  somewhere else anyway — i.e. SR-1 is still needed underneath it.
- **Option SR-3 — SOPS/age-encrypted `infra/.env.enc` committed to the repo. Rejected, not forked.** The
  repo is **public** and git history is permanent, so this publishes a brute-forceable blob forever, and
  the key still has to live outside git. It contradicts the `scrub-identity` lesson in CLAUDE.md.

**Recommendation: SR-1 now, SR-2 as a later convenience.** Either way the finding is recorded: the gap is
real, and "the off-site backup covers the DB but not the credentials" belongs in
[`infra/README.md`](../../infra/README.md) in the same change as the first release runbook.

## Options Considered

### Versioning scheme (settled — one repo version)
| Dimension | One repo-wide version (**chosen**) | Per-module semver |
|---|---|---|
| Fits today's poms | **Yes** — all 72 already inherit one version | No — 71 versions to introduce + a flatten plugin |
| Deploy reality | One compose project, one box, one operator | Models independent deployables that don't exist |
| Changelog | One `CHANGELOG.md` | 71 drift sources |
| `release-version` skill fit | Direct (one tag, one delta) | Needs a per-module delta the skill has no notion of |
| Cost of a bump | A tag (poms untouched) | 72 files, or `${revision}` plumbing |

### RG — build location + registry
| Dimension | RG-A host build, no registry (**first release**) | RG-B Actions + GHCR (**end state**) | RG-C local registry |
|---|---|---|---|
| Native arm64 | **Yes** | Needs arm64 runners (verify) or slow QEMU | Yes |
| Reproducible artifact | No — built from the working tree | **Yes** — from a tagged commit, clean env | No (same build, stored) |
| Competes with the model for RAM/CPU | **Yes** | **No** — the Mac only pulls | Yes |
| Rollback to a previous version | By local tag (lost if pruned/disk dies) | **`docker pull` any past tag** | By local tag (same disk) |
| New credential on the host | None | A GHCR read token in `infra/secrets/` | None |
| New moving parts | None | A release workflow (CI change) | A registry container + volume |
| Cost at ~50 images / at ~13 host-units | Minutes on the box / much less | Heavy CI / **reasonable** | same as RG-A |
| Blocks the Mac's arrival | **No** | Yes (needs the workflow first) | No |

### MG — migration rollback posture
| Dimension | MG-1 forward-only + expand/contract (**recommended**) | MG-2 reversible changesets | MG-3 hybrid |
|---|---|---|---|
| Image rollback needs a DB rollback | **No** (that is the point) | No, but one exists | No |
| Cost per migration PR | A compatibility rule to apply | A `rollback:` + a rollback test lane | Only on destructive ones |
| Recovers data a destructive migration dropped | No (nothing can) | **No — but it looks like it does** | No |
| Destructive change | Takes two releases | One release | One release |
| Honest about its own limits | **Yes** | No | Yes, and nearly a no-op |
| Mechanically checkable later | Yes (same-release drop/narrow lint) | Only "a block exists", not that it works | Partially |

### SR — secrets recovery
| Dimension | SR-1 host + password manager (**recommended**) | SR-2 rclone `crypt` off-site | SR-3 SOPS in git (**rejected**) |
|---|---|---|---|
| Machinery | **None** | One remote + a passphrase | A tool + a key + a committed blob |
| Survives a dead disk | Yes, if the copy is current | **Yes, automatically** | Yes |
| Still needs an out-of-band secret | — | Yes (the crypt passphrase) | Yes (the age key) |
| Fits a **public** repo | Yes | Yes | **No** — permanent public ciphertext |

## Consequences

**Easier:**
- A release becomes a **named, reproducible state** instead of "whatever `--build` produced". `v0.1.0` means
  something: a commit, a changelog, a set of image tags, a DB tag.
- **Rollback becomes a two-line operation** — point `AI_LIFE_IMAGE_TAG` at the previous tag, `up -d` — and
  under MG-1 it needs no DB action at all, which is the only property that matters at 02:00.
- The release unit is decoupled from the module tree, so **ADR-0006 can collapse 50 containers into 13
  without touching the release policy** — the same way the module tree is already decoupled from the runtime
  topology.
- The `built+green` vs `live` distinction STATUS.md maintains in prose gains a mechanism: the host half of
  the gate is what the distinction has been describing all along.

**Harder / to watch:**
- **The hot set restarts on every release.** `libs/inbox` + Postgres-held dialog state make that safe, not
  invisible — releases belong in a quiet window, and the health gate must roll back rather than leave the
  household with a half-started assistant.
- **A second deploy-time source of truth is now possible** and must not be allowed: the image tag in `.env`,
  the git tag, and `CHANGELOG.md` must agree. The `project-version` coupling in `.skills/change-map.yaml`
  plus a lint candidate (every compose `image:` parameterised; every deployable unit has a Dockerfile) is
  how that stays checked instead of remembered.
- **Under RG-A the deploy box is also the build box.** Accepted deliberately for the first release, but it
  is the reason RG-B exists; re-measure a full release build against the resident model once (settled
  item 6's host half is the natural place) and let that number drive the RG-B cutover.
- **The golden lane becomes a release gate, not just a test lane.** It currently cannot pass on the dev VDI
  at all (throughput-bound, STATUS §Parked). If it is flaky on the Mac the release path stalls, so its first
  full green run there is effectively a prerequisite of the first release.
- **`tagDatabase` is a new habit.** One changeset per release boundary is trivial to forget and worthless
  retroactively.
- **Nothing here is exercised until the Mac exists.** This ADR is design, and its first real test is the
  first release — which is exactly why the restore rehearsal and the health gate are acceptance criteria
  rather than documentation.

## Change propagation — what rides with the release machinery

Per CLAUDE.md §Change-propagation map, the artifacts that must move **in the same PR** as any slice of
[release.md](../release.md). This list is the coupling; the mechanizable subset should become a lint entry as
it stabilises.

| when a slice touches… | these move with it |
|---|---|
| the version identity | new root `CHANGELOG.md` · a `project-version` coupling in `.skills/change-map.yaml` (the `release-version` skill's step 4 reads it) · the version→`/actuator/info` wiring's module README |
| image tags | `infra/docker-compose.yml` (every `image:` → `${AI_LIFE_IMAGE_TAG:-local}`) · `infra/.env.example` + `infra/.env.mac.example` (the new key) · `infra/README.md` · `scripts/start-mac.sh` + `scripts/start-win.ps1` (today's `--build` is no longer how a release starts) |
| a deployable unit's identity | `deploy/<unit>/Dockerfile` + its compose service + `deploy/<unit>/README.md` · `plans/topology-map.md` · [ADR-0006](ADR-0006-runtime-topology-footprint.md) item 4 · `check-consistency.sh` check 13's hot-set closure (host-units change the `depends_on` graph) |
| the deploy/rollback path | `scripts/` (the new deploy script joins `bootstrap-mac.sh` / `start-mac.sh`) · `infra/README.md` §deploy + §rollback + the backup-scope note · `plans/lifecycle.md` (the supervisor section must state it is **not** the deployer) |
| migration posture | `plans/PATTERNS.md` §new migration (the expand/contract rule) · `infra/liquibase/db.changelog-master.xml` (the `tagDatabase` boundary) · `infra/README.md` §Database backups (the rehearsed restore) |
| secrets | `infra/.env{,.mac}.example` (key, never a value) · `infra/README.md` · `.gitignore` if a new secret-bearing path appears · the `scrub-identity` / gitleaks posture (a registry token must not reach a committed script) |
| build location (fork RG-B only) | `.github/workflows/` (a release workflow — **out of scope for this ADR**) · `infra/README.md` (pull instead of build) · `infra/secrets/` (the GHCR credential) |
| any of the above | root `README.md` Status line · `plans/STATUS.md` → `plans/HISTORY.md` at PR end · `plans/INDEX.md` row (statusless) · this ADR's Status header when a fork is picked |

**Deliberately unchanged:** `tools/agent-skills/skills/release-version/SKILL.md`. The procedure is portable
and stays generic; every ai-life-specific fact lands in `.skills/change-map.yaml` + `release.md`. A
sibling-repo chore is **not** created by this work (unlike the LC-4 `/v1/model-profile` coupling, which does
have a cross-repo half).

## Action Items

1. [ ] **Owner picks RG, MG and SR** (this ADR moves to Accepted with the picks recorded). No
   `architecture.md` §Locked-decisions change for RG/SR; **MG is a standing authoring rule** and so belongs
   in `PATTERNS.md` §new migration once picked.
2. [ ] **Slice the machinery** per [release.md](../release.md) — WHEN/THEN first, one PR per slice, in the
   order RL-1 → RL-2 → RL-3 → RL-4 (the minimum for a first real release), with RL-5/RL-6 fork-gated.
3. [ ] **Verify the RG-B dependency before committing to it:** free hosted `linux/arm64` runners for public
   repositories, and the intended GHCR package visibility. If the runners are not available, RG-B's cost
   changes materially (QEMU) and the staging recommendation should be re-read with that in mind.
4. [ ] **Rehearse the restore on the Mac, once, and write the result down** (`infra/README.md`) — including
   how long it took and what was lost. Until then the rollback plan is untested.
5. [ ] **Measure a full release build against the resident model** on the Mac (reuse
   `scripts/measure-footprint.sh`). That number is what decides when RG-A stops being acceptable.
6. [ ] **Extend `check-consistency.sh`** once the machinery stabilises: every compose `image:` tag is
   parameterised, and every deployable unit named in `topology-map.md` has a `Dockerfile` + a compose
   service. Per CLAUDE.md, a mechanically checkable coupling belongs in the lint, not in memory.
7. [ ] **Cut `v0.1.0` as the first release** once the Mac is up and settled item 6's gate is green end to
   end, and record the `built+green` → `live` flip in `STATUS.md` §Deployment reality.

## Notes
Eleventh ADR. It introduces **no new shared contract and no new runtime layer** — release machinery sits
beside the system, not inside it. It is flagged here rather than decided inside a deploy script because the
three forks have consequences that outlive any script: a registry choice is a dependency, a migration
posture is a standing authoring rule, and a secrets-recovery choice is the difference between a recoverable
box and a lost one. Until Accepted it is a proposal; no release script is written against it yet.

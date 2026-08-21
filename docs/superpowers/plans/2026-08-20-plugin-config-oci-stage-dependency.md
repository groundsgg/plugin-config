# Plugin Config OCI Stage Dependency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the existing Velocity `plugin-config` runtime as a data-only OCI image, then pin it before `plugin-resourcepacks` in the platform bundle and Stage overlay.

**Architecture:** `plugin-config` remains its own Velocity plugin; it is not shaded into `plugin-resourcepacks`. Release Please owns the semantic version and tag. The Docker image contains exactly the final shaded Velocity JAR at `/jar/plugin.jar`; the existing `plugin-velocity-jar` chart serves it. Bundle and Stage use fixed releases. Stage supplies `GROUNDS_ENVIRONMENT=stage` to satisfy the published resourcepacks plugin and keeps its edge-only bootstrap variable.

**Tech Stack:** Kotlin/JVM 25, Gradle Shadow, GitHub Release Please, BuildKit, GHCR, Platform Bundle YAML, Helm values.

**Spec:** `/home/lukas/grounds/.worktrees/bundle-resourcepacks-stage/docs/superpowers/specs/2026-08-20-resourcepacks-stage-integration-design.md` (amended by the verified runtime prerequisite below).

## Global Constraints

- `plugin-config` is a mandatory Velocity dependency of `plugin-resourcepacks`; the two plugins must be loaded in that order.
- No plugin is embedded into another plugin image or JAR.
- Release Please, never a manually created tag, publishes the new `plugin-config` OCI version.
- Docker build credentials are BuildKit secrets only and never remain in an image layer.
- Data-only final images contain `/jar/plugin.jar` and no entrypoint or command.
- Bundle and Stage use literal released SemVer pins, never moving tags or variables.
- Stage alone sets `RESOURCE_PACK_DEFAULT_CHANNEL=edge`; both its resourcepacks registration scope and the plugin's required deployment environment are `stage`.
- No Config Admin, R2, CDN credential, seed Job, or production overlay is introduced.

## Task 1: Make `plugin-config` OCI-Releasable

**Files:**
- Create: `Dockerfile`
- Modify: `.github/workflows/release.yml`
- Modify: `release-please-config.json`
- Create: `version.txt`
- Modify/Test: root Gradle version configuration and an artifact-contract test/task.

- [ ] **Step 1: Write failing release/artifact contract tests**

Assert the effective project version is strict SemVer from `version.txt` unless a strict `-PversionOverride` is supplied. Assert the final `velocity:shadowJar` output has a Velocity metadata file with that version and plugin id `plugin-config`. Assert the Dockerfile copies the root Gradle inputs plus `common/` and `velocity/`, uses a required BuildKit `github_token` secret, and places the single shaded JAR at `/jar/plugin.jar` without an entrypoint.

- [ ] **Step 2: Run the focused RED test**

Run the existing Gradle test/task that reads the release files. It must fail because no version source, Dockerfile, OCI release job, or artifact contract exists.

- [ ] **Step 3: Implement the minimal release path**

Adopt the proven `plugin-resourcepacks` data-image structure: JDK 25 build stage, `:velocity:shadowJar`, BuildKit secret for GitHub Packages resolution, then an Alpine final stage with exactly `/jar/plugin.jar`. Copy `common/` because `velocity` has a local project dependency on it. Keep Maven publication intact. Add a Docker reusable release job with only `contents: write` and `packages: write`; forward the existing packages-read secret exactly as the Maven job does. Make Release Please update `version.txt` and use the value as the Gradle version so tag/image/JAR metadata agree.

- [ ] **Step 4: Run focused GREEN and full local verification**

Run the release/artifact contract, `./gradlew --no-build-cache clean check`, build the data image locally where Docker is available, inspect `/jar/plugin.jar`, and verify no token appears in image history or configuration.

- [ ] **Step 5: Commit**

Commit a signed conventional `fix(release): publish plugin config image` change. Do not create a tag.

## Task 2: Release and Capture the Config Plugin Version

**Files:** Release-maintained version files only.

- [ ] **Step 1: Open, validate, and merge the release-path PR**

Push only the signed Task 1 branch and open one PR. Merge only after its CI is green and review findings are closed.

- [ ] **Step 2: Let Release Please create the patch release**

Merge the generated Release Please PR; do not hand-create a tag. Watch the tag-triggered Maven and OCI jobs.

- [ ] **Step 3: Record the exact release**

Capture `PLUGIN_CONFIG_VERSION` from the successful immutable OCI tag and inspect the JAR metadata inside it. Later tasks use this literal.

## Task 3: Correct the Bundle Dependency Order

**Files:**
- Modify: `/home/lukas/grounds/.worktrees/bundle-resourcepacks-0-1-2/bundle.yaml`
- Modify/Test: `/home/lukas/grounds/.worktrees/bundle-resourcepacks-0-1-2/scripts/validate-bundle.py`

- [ ] **Step 1: Write failing bundle contract assertions**

Require one literal-pinned `plugin-config` component using `plugin-velocity-jar`, its released image, and `PLUGIN_CONFIG_VERSION`. Require both `velocity` and `velocity-2` plugin lists to contain it exactly once before `plugin-resourcepacks`. Preserve literal resourcepacks `0.1.2` and prohibit all `RESOURCE_PACK_DEFAULT_CHANNEL` entries in the neutral bundle.

- [ ] **Step 2: Run RED, then implement and run GREEN**

Run `python3 scripts/validate-bundle.py`; it must fail before the component exists. Add the minimal component/resource envelope and ordered entries, then rerun the validator and `git diff --check`.

- [ ] **Step 3: Commit**

Commit a signed `fix(bundle): add config dependency for resourcepacks` change.

## Task 4: Correct Stage Runtime Prerequisites

**Files:**
- Create: `/home/lukas/grounds/.worktrees/deploy-resourcepacks-stage-0-1-2/environments/stage/components/plugin-config/component.yaml`
- Create: `/home/lukas/grounds/.worktrees/deploy-resourcepacks-stage-0-1-2/environments/stage/components/plugin-config/values.yaml`
- Modify/Test: `/home/lukas/grounds/.worktrees/deploy-resourcepacks-stage-0-1-2/environments/stage/components/velocity/values.yaml` and the Stage resourcepack topology test.

- [ ] **Step 1: Write failing Stage assertions**

Require the literal released config image pin; require `plugin-config` exactly once before `plugin-resourcepacks`; require exactly one `GROUNDS_ENVIRONMENT=stage` and exactly one `RESOURCE_PACK_DEFAULT_CHANNEL=edge`; reject missing, duplicate, moving, noncanonical, or production forms. Assert no Config Admin/R2/CDN variables are added.

- [ ] **Step 2: Run RED, implement, and run GREEN**

Run the focused Stage topology test before edits, then add the small plugin component and two Velocity variables/order changes. Run the test suite and CI Python component validation twice.

- [ ] **Step 3: Commit**

Commit a signed `fix(stage): load config before resourcepacks` change.

## Task 5: Cross-Repository Acceptance

- [ ] **Step 1: Independently review all three diffs**

Verify immutable version agreement, plugin load order, final image JAR metadata, Stage-only environment, no credentials, and create-only Config Service bootstrap semantics.

- [ ] **Step 2: Open PRs in dependency order**

Merge `plugin-config` release first, then bundle and Stage PRs. Wait for each repository CI, including the authenticated Helm registry check.

- [ ] **Step 3: Observe Stage after Argo sync**

Verify Velocity loads both plugins; `network/stage/resourcepacks/global` is created only if absent with Edge source; a pre-existing override is unchanged; client reaches READY; a later Edge pointer update changes the resolved target without a deployment revision.

## Self-Review

- Spec coverage: Tasks 1–2 close the missing OCI artifact; Tasks 3–4 close the dependency/load-environment gaps; Task 5 preserves deployment-order and create-only acceptance.
- Placeholder scan: no task depends on an unspecified image, version, credential, or tag creation path.
- Type/version consistency: task 2 produces literal `PLUGIN_CONFIG_VERSION`; tasks 3 and 4 consume the same literal and retain resourcepacks `0.1.2`.

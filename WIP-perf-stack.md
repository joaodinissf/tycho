# WIP — Tycho download/resolution performance stack

> **Living status doc.** Nursed on branch `feat/parallel-artifact-prefetch` until the stack is
> complete. **Remove this file before opening any upstream PR** — it is working state, not a
> deliverable. This is the canonical source of truth for the effort (supersedes the scratch plan in
> `~/.claude/plans/`).

## Working rules (do not violate)
- Work in the **fork** `joaodinissf/tycho` (remote `origin`); upstream is `eclipse-tycho` (remote `upstream`).
- **Push to the fork to save work; DO NOT open any PR or issue anywhere until explicitly told.**
- **Signed** commits (SSH signing — never `--no-gpg-sign`), **Conventional Commits**.
- PR2/PR3 are **stacked commits on this same branch** (not separate branches), per user direction.
- **TDD**: every behavior change = failing test first (RED), proven to fail for the right reason
  ("teeth"), then GREEN, then docs. No regressions (full `tycho-core` suite must stay green).

## Branch state (update on every commit)
| Commit | What |
|---|---|
| `a4e5bb52a` | PR1 — parallelize artifact prefetch |
| `6591062e8` | PR2 — parallel metadata-repository loading |
| `e510e94ac` | PR2 — integration test vs real p2 manager |
| `30c597ae7` | WIP status doc |
| `15ed5ba21` | WIP — full steps ledger |
| `5f032c4a1` | PR3 — opt-in trust of cached qualified artifacts (default off) |

`origin/feat/parallel-artifact-prefetch` == local HEAD. No PRs opened.

## Final tally (W1)

**Stack:** PR1 `a4e5bb52a` (parallel artifact prefetch + default 4→8) · PR2 `6591062e8`+`e510e94ac`
(parallel metadata load + real-manager integration test) · PR3 `5f032c4a1` (opt-in, default-off
trust of cached qualified artifacts). On fork `joaodinissf/tycho`, branch
`feat/parallel-artifact-prefetch`. No PRs opened.

**End-to-end speedup (cold, 3 repos + 100 bundles, default config) — two methods agree:**
| Method | today/unpatched | branch/patched | speedup |
|---|---|---|---|
| **Pure stamp** (unpatched pre-PR1 binary vs patched binary) | 155 s (Maven 2:22) | 64 s (Maven 1:02) | **~2.4×** |
| **Flag isolation** (same binary, `threads=1` vs `8`) | 179 s | 68 s | **~2.6×** |

**Piecewise (isolated):**
- PR1 artifact prefetch — synthetic ~4× (8 = sweet spot, 16 no better); real single-repo 100-bundle cold 120→25 s (~4.8×).
- PR2 metadata load — real 3-repo cold 62→20 s (~3.1×); integration test = 6 concurrent loads vs real Equinox manager, thread-safe.
- PR3 — re-downloads 98→20 (~80% fewer) but **wall-time flat** (warm CDN ~1 s; 0.25 s/conn proxy 12 vs 11 s — keep-alive amortizes per-connection cost). Kept **opt-in, default-off** as a *bandwidth* saver, not a perf win.

**Correctness:** 613 `tycho-core` tests, 0 failures; strict TDD (teeth→green→docs); real builds produce identical output. No `tycho-api`/`spi` or p2/Equinox changes; load-bearing serial points (SAT solver, slicer, Equinox resolve, ref dedup) untouched.

**Honest scope:** all wins are **cold-path** (downloads). **Warm builds are unaffected** by this stack — their ~2.8 s is *re-resolution* (re-parse + SAT), which is the deferred **#5** (bigger lever, invasive). Speedups scale with network/repo conditions; single-run numbers (network variance), but both methods + large gaps make the ~2.4–2.6× direction solid.

## Steps ledger (all — done / todo / deferred)
| Phase | # | Step | Status | Notes |
|---|---|---|---|---|
| Setup | S1 | Clone `eclipse-tycho/tycho` | ✅ Done | |
| Setup | S2 | Workflow team #1 — serial-resolution investigation | ✅ Done | dead `DOWNLOAD_EXECUTOR` |
| Setup | S3 | Workflow team #2 — re-download root-cause | ✅ Done | #651 reframed |
| Setup | S4 | Plan approved; fork + remotes wired | ✅ Done | origin=fork |
| Setup | S5 | WIP status doc committed | ✅ Done | `30c597ae7` |
| PR1 | 1a | RED `ArtifactPrefetcherTest` (teeth) | ✅ Done | 3 tests |
| PR1 | 1b | GREEN `ArtifactPrefetcher` + wire `RepositoryReferenceTool` | ✅ Done | |
| PR1 | 1c | Default 4→8 + docs | ✅ Done | |
| PR1 | 1d | Gates: unit 3/3, full 607/0/0 | ✅ Done | |
| PR1 | 1e | Commit (signed) + push | ✅ Done | `a4e5bb52a` |
| PR2 | 2a | RED `MetadataRepositoryPrewarmerTest` (teeth) | ✅ Done | 3 tests |
| PR2 | 2b | GREEN prewarmer + wire `gatherExternalInstallableUnits` | ✅ Done | walk byte-identical |
| PR2 | 2c | Docs (`RELEASE_NOTES`) | ✅ Done | |
| PR2 | 2d | Gate: full 610/0/0 | ✅ Done | |
| PR2 | 2e | Commit (signed) + push | ✅ Done | `6591062e8` |
| PR2 | 2f | Integration test vs real p2 manager | ✅ Done | `e510e94ac` |
| Bench | B1 | PR1 synthetic sweep (~4×) | ✅ Done | 8 = sweet spot |
| Bench | B2 | PR1 real cold A/B 120→25s (~4.8×) | ✅ Done | |
| Bench | B3 | PR2 real cold A/B 62→20s (~3.1×) | ✅ Done | |
| Bench | B4 | Warm-build profiling (~2.8s re-resolution) | ✅ Done | → #5 |
| PR3 | 3a | Phase-0 value gate (metadata analysis: cross-key dedup=0; 72 same-key byte-drift = re-sign/timestamps, never code) | ✅ Done | content-store dropped; pivoted to qualified-trust |
| PR3 | 3b | RED `MirroringArtifactProviderQualifierTest.hasQualifier` | ✅ Done | qualified vs non-qualified |
| PR3 | 3c | GREEN `hasQualifier` + guard in `isFileAlreadyAvailable`; opt-in flag default **off** | ✅ Done | STRICT carve-out |
| PR3 | 3d | Docs (`SystemProperties.md`, `RELEASE_NOTES.md`) + full suite 613/0/0 | ✅ Done | |
| PR3 | 3e | A/B: re-downloads 98→20; **wall flat** (warm CDN & 0.25s/conn proxy — keep-alive amortizes) → kept **opt-in default-off** bandwidth saver | ✅ Done | `5f032c4a1` |
| Wrap | W1 | Final stack summary / tally (both methods ~2.4–2.6× cold) | ✅ Done | see "Final tally" |
| Wrap | W2 | Draft #5 upstream design proposal | ⏳ Todo (optional) | |
| Wrap | W3 | Remove this WIP doc before opening any PR | ⏳ Todo (at completion) | |
| Defer | #4 | Eclipse/PDE upstreaming + shared p2 loader | ⏸️ Deferred | |
| Defer | #5 | Warm-resolution caching (feasibility B, invasive) | ⏸️ Deferred | |
| Defer | P4 | Cross-stage re-download (director/surefire) | ⏸️ Deferred | |
| Defer | P6 | HTTP revalidation after 1h / header-poor mirrors | ⏸️ Deferred | |
| Defer | P7 | Cross-process HTTP-cache lock (#663 half-fixed) | ⏸️ Deferred | |

## Confirmed problems (evidence)
| # | Problem | Evidence | Status |
|---|---|---|---|
| P1 | Artifact prefetch serial; bounded `DOWNLOAD_EXECUTOR` was dead code (zero callers since commit `3098bd215`, 2022-12-03) | `RepositoryReferenceTool.java:138`; `TychoRepositoryTransport.java:59-73,239` | **Fixed (PR1)** |
| P8 | Default concurrency 4 — far below peers (yarn 8 / pnpm 16 / bun 48 / uv 50), and unreachable | `tycho.p2.transport.max-download-threads` | **Fixed (PR1 → 8)** |
| P2 | `fetchArtifact()` async-in-name-only | `DefaultArtifactDescriptor.java:100-121` | Not addressed (low value — see PR2 note) |
| P3 | "#651" in-build re-download | (largely a non-issue: shared session-wide `LocalArtifactRepository` + per-key lock already dedup bytes) | Reframed; not pursued |
| P5 | No content-hash dedup (key+property only; SHA-256 only validates) | `MirroringArtifactProvider.java:120-124,434-482` | **PR3 (pending)** |
| P4 | Cross-stage re-download (director/surefire re-provision) | `DirectorApplicationWrapper.java:74-76` | Deferred (task #4-adjacent) |
| P6 | HTTP revalidation after `MIN_CACHE_PERIOD` (1h); header-poor mirrors → full re-fetch | `SharedHttpCacheStorage.java:56,228-297,345-378` | Deferred |
| P7 | HTTP cache not OS-locked (cross-process race; #663 only half-fixed) | `SharedHttpCacheStorage` | Deferred |

## PR stack

### ✅ PR1 — Parallelize the artifact prefetch (P1, P8) — DONE
- `ArtifactPrefetcher` (`tycho-core/.../p2tools/`): fan-out of `getLocation(true)` over the shared
  bounded pool, TCCL propagation, fail-after-all-complete (rethrows original `MirroringFailedException`).
- `RepositoryReferenceTool.java:138` delegates to it; default raised **4→8** in `TychoRepositoryTransport`.
- Docs: `SystemProperties.md`, `RELEASE_NOTES.md`. Tests: `ArtifactPrefetcherTest` (3, teeth-proven).
- Gates: unit 3/3; full `tycho-core` 607/0/0.

### ✅ PR2 — Parallelize metadata-repository loading — DONE
- `MetadataRepositoryPrewarmer` (`tycho-core/.../p2resolver/`): loads top-level `completeRepositories`
  concurrently over the shared pool **before** the existing walk; per-worker `NullProgressMonitor`
  (shared monitor not thread-safe), TCCL propagated, failures swallowed.
- Wired at top of `TargetPlatformFactoryImpl.gatherExternalInstallableUnits` (before the serial loop).
  **The sequential walk is byte-identical** — relies on p2 manager load-caching; ordering/dedup/recursion unchanged.
- Verified safe: `RemoteMetadataRepositoryManager` delegates to caching p2 manager; `MavenAuthenticator`
  uses `ThreadLocal` load stack.
- Tests: `MetadataRepositoryPrewarmerTest` (3, teeth-proven) + `MetadataRepositoryPrewarmerIntegrationTest`
  (6 concurrent loads vs **real** Equinox manager → thread-safety proven).
- Gates: unit 3/3 + integration 1/1; full `tycho-core` 610/0/0.

### ⏳ PR3 — Opportunistic content-hash (SHA-256) dedup across keys (P5) — PENDING
- Idea: before downloading a key, consult a sha-256→file content index (sha-256 already in p2 index,
  read at `MirroringArtifactProvider.fileMatchesProperties:286-289`); on hit link/copy instead of download.
  **Opportunistic** — only when descriptor publishes sha-256; else today's path.
- RED tests: `sameContentTwoClassifiersDownloadedOnce`, `missingChecksumFallsBackToNormalDownload`.
- Riskiest of the three; do last. **Open question:** value may be modest (already-deduped bytes) — re-assess before building.

## Benchmarks / validation (empirical)
- **PR1 synthetic** (40 real Eclipse bundles, warm CDN, harness `/Users/joao/Git/tycho-bench/DownloadBench.java`):
  serial 11.7s → 8-way 3.0s (**~4×**); 16 threads no further gain → **default 8 validated**.
- **PR1 real cold A/B** (toy eclipse-repository, 100 bundles, `~/.m2/p2` cleared each run):
  `threads=1` 120s → `threads=8` 25s (**~4.8×**), identical 101-plugin output.
- **PR2 real cold A/B** (3 Eclipse release repos, 5-bundle category): `threads=1` 62s → `threads=8` 20s (**~3.1×**).
- **PR2 integration test**: 6 concurrent loads against the real p2 manager — all OK (thread-safety).
- **Warm-build profiling** (CSV via `co.leantechniques:maven-buildtime-extension:3.0.5`, 3 repos, nothing
  changed, 0 network): warm Maven 3.6s = **~2.8s target-platform RE-RESOLUTION** (afterProjectsRead;
  re-parse cached metadata + Slicer + SAT, not a mojo) + 0.76s mojos + ~1.4s JVM/Maven startup. → task #5.

## Deferred / future (NOT in this stack)
- **#4 Eclipse/PDE**: make the same parallel optimization in PDE target resolution; ideal end-state is a
  shared parallel-loader at the p2 layer. Tycho `TargetPlatformFactoryImpl` and PDE `org.eclipse.pde.core.target`
  are *different orchestrations* over the *same* p2/Equinox core.
- **#5 warm-resolution caching** (the big warm win): persist/short-circuit resolution across invocations.
  **Feasibility = (B) achievable but invasive.** Blockers: `IInstallableUnit` not `Serializable` (need full
  requirement/capability model, ≈ persisting a fast-load `content.xml`); SAT solve re-runs + mutable reactor
  state complicate invalidation. Cache **key is tractable** (repo URLs + on-disk metadata fingerprints, no
  extra network within 1h window). **Note:** PR2 already parallelizes the warm metadata *parse* (prewarm runs
  `loadRepository`, which parses); the irreducible warm cost is the single-threaded SAT solve + that resolution
  re-runs at all. **Recommendation: upstream design proposal, not a speculative PR now.**
- **P6 / P7**: revalidation chatter after 1h; cross-process HTTP-cache lock.

## Test conventions
- **JUnit 4** (`org.junit.*`, `assertThrows`), **Mockito 5**, base `TychoPlexusTestCase` (`lookup(Class)`).
- Download-count seam: `HttpServer` (`@Rule`) + `getAccessedUrls(path)`. Local repos: `TemporaryLocalMavenRepository`,
  `TestRepositoryContent`, `src/test/resources/repositories/`. Concurrency: hand-rolled `CountDownLatch` fakes.

## Build / bench cheat-sheet
- One-time (cache siblings for fast offline gates): `mvn -pl tycho-core -am install -DskipTests`
- Unit gate: `mvn -o -pl tycho-core test -Dtest=<TestClass> -Dsurefire.failIfNoSpecifiedTests=false`
- Regression gate: `mvn -o -pl tycho-core test`
- Build all plugins for the toy: `mvn -T1C install -DskipTests` (at repo root)
- Toy project: `/Users/joao/Git/tycho-bench/toy` (eclipse-repository; repos + `repo/category.xml`).
- Cold A/B: clear `~/.m2/repository/p2` + fresh `-Dtycho.p2.transport.cache=<tmp>` per run; vary
  `-Dtycho.p2.transport.max-download-threads=1|8`.
- First test run per fresh setup must be **online** (downloads the surefire JUnit provider) before `-o` works.

## Next decision
PR3, or wrap-up/summary. Optionally draft the #5 upstream design proposal while fresh. Update this file
and the "Branch state" table after each commit.

<!--
  DRAFT — NOT FOR MERGE. Working design proposal for an eventual upstream discussion/issue.
  Lives on the perf branch alongside WIP-perf-stack.md and is removed/relocated before any PR.
-->

# RFC (draft): Cross-invocation caching of target-platform resolution

**Status:** draft / for discussion — **not** an implementation PR.
**Author:** (perf-stack work) · **Audience:** Tycho maintainers.

## 1. Motivation

Warm Tycho builds (nothing changed, all artifacts already on disk) still spend a large, fixed amount
of time re-running target-platform **resolution** on every invocation, even though the inputs and the
result are identical to the previous build. Downloads are zero when warm; the cost is pure computation
that is thrown away at the end of each Maven invocation.

### Measured breakdown (value gate)

Throwaway timing probes (since reverted) around the two resolution stages, warm build of a 3-repository
toy (`~/.m2/p2` populated, no network):

| Stage | Where | Warm cost |
|---|---|---|
| Parse / gather metadata | `TargetPlatformFactoryImpl.gatherExternalInstallableUnits` | ~0.25–0.45 s |
| **SAT solve** (p2 `Projector`, per environment) | `P2ResolverImpl.resolveTargetDependencies` → `ProjectorResolutionStrategy` | **~1.3–1.5 s** |

A 4-environment warm build measured `2428 ms` and `1862 ms` in the per-environment solve loop — ~45 % of
a 9.7 s build, scaling ~0.5–0.6 s per environment.

**Two conclusions:**
1. The parse/gather stage is already small and is parallelized (the metadata pre-warm shipped earlier in
   this branch). **Caching only the parsed metadata IU set would be wall-flat** — it does not touch the
   dominant SAT cost. This option was therefore dropped.
2. The dominant warm cost is the **SAT solve**, whose *result* is never persisted across invocations.
   Two independent levers attack it:
   - **Parallelize the per-environment solves** (implemented in this branch as PR4 — contained, no caching,
     no correctness risk). Helps multi-environment builds; does nothing for single-environment modules and
     does not remove the work, only overlaps it.
   - **Cache the SAT result across invocations** (this RFC) — removes the work entirely on a warm rebuild,
     but is correctness-critical and invasive. Hence: design first, with maintainer review.

## 2. Proposal

Persist the resolution **result** (the resolved set of `IInstallableUnit`s, per environment) to disk,
keyed by a fingerprint of every input that determines it, and short-circuit resolution on a cache hit.

Stage it so the risky part is opt-in and incremental:

- **Phase 1 (metadata content):** persist the gathered external IU set (`TargetDefinitionContent`) so the
  parse/gather stage can be skipped. *Low value on its own (see §1) — include only if it falls out of the
  infrastructure for Phase 2.*
- **Phase 2 (resolution result):** persist the per-environment resolved IU set and skip the SAT solve on a
  cache hit. This is where the ~1.4 s lives.

### Serialization

`IInstallableUnit` is not `java.io.Serializable`, so do **not** serialize IU objects directly. Reuse the
in-tree, supported path: `org.eclipse.tycho.p2tools.MetadataSerializable` →
`MetadataRepositoryIO.write(...)` produces a standard p2 `content.xml`; reading back is a normal p2
`loadRepository(...)` (fast). `RepositoryReferenceTool` already writes resolved target-platform IUs this way,
so the format and round-trip are proven.

## 3. Cache key — the hard part

A persisted result may be reused **only** if every input to the solve is identical. The in-session cache key
`TargetDefinitionResolverService.ResolutionArguments` (hardened for stability by issue #496) is the right
*template*, but it is **not** directly disk-stable: it keys on the live `IProvisioningAgent` by object
identity. A cross-invocation key must drop the agent and instead capture content fingerprints.

| Input | Source | Stability | Fingerprint |
|---|---|---|---|
| p2 repository URLs | `TargetPlatformConfiguration` / `.target` | stable | URL list (ordered) |
| repository metadata content | remote `content.jar`/`artifacts.jar` (cached on disk) | stable within the 1 h `MIN_CACHE_PERIOD`; otherwise revalidate | on-disk metadata hash / ETag |
| target-file content | `.target` file | stable | file content hash |
| environments (os/ws/arch) | `TargetPlatformConfiguration` | stable | tuple list |
| execution environment | `ExecutionEnvironmentResolutionHints` | stable | profile id + mandatory units |
| filters / include modes | `TargetPlatformConfiguration` | stable | serialized config |
| **reactor project IUs** | `ReactorProject.getDependencyMetadata(SEED/RESOLVE)` | **mutable — rebuilt every `mvn clean`** | per-project IU hash |
| **additional requirements** | `P2ResolverImpl.additionalRequirements` | **mutable — set per project** | requirement hash |

The last two rows are the danger. They change whenever an upstream reactor module is rebuilt, and they are
set per-project at resolve time. A cache that misses any of them returns a **silently wrong** resolution
(missing/extra dependencies, no error). The key must be conservative: when in doubt, miss.

## 4. Invalidation hazards / open questions

- **Reactor coupling:** a change in one reactor module can change the resolution of dependents without any
  target-file/repository change. The key must include a fingerprint of each contributing reactor IU set.
- **Metadata freshness:** within `MIN_CACHE_PERIOD` (1 h) on-disk metadata is reused without revalidation; a
  result cache must not outlive the metadata it was computed from. `-U` must bypass it.
- **p2 internal versions:** a persisted `content.xml` is portable, but the *meaning* of a solve can shift
  across p2/Tycho versions — key should include a Tycho/p2 version stamp.
- **Daemon/IDE reuse:** in long-lived processes the in-memory caches already help; the disk cache mainly
  benefits fresh CLI invocations.
- **Correctness gate:** any implementation needs an equivalence harness proving cached == freshly-computed
  across a representative corpus, plus a kill switch.

## 5. Prior art
- **Bug 533747** — target files were parsed repeatedly; fixed by the in-session
  `TargetDefinitionResolverService.resolutionCache` (per-Maven-session only).
- **Issue #496** — `ResolutionArguments#hashCode` instability; fixed by a deterministic key. Direct evidence
  that getting this key right is subtle even *within* a session; a cross-invocation key is materially harder.

## 6. Recommendation

Land this **upstream as a design discussion first**, not as a speculative PR. The serialization path and the
key template already exist in-tree, so a prototype is feasible — but the correctness surface (mutable reactor
inputs, silent-staleness risk) warrants maintainer agreement on the key and an equivalence test corpus before
implementation. The contained, low-risk win (parallel per-environment solves, PR4) is shipped separately and
does not depend on this.

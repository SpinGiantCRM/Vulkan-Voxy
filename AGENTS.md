# AGENTS.md

## Project orientation
- Vulkan-Voxy is a fork of Voxy being ported to VulkanMod/Beryl.
- The default branch is `dev`.
- MDIC/OpenGL code is intentionally kept as reference code while Vulkan/Beryl work continues.

## General working guidance
- Start by identifying the area most relevant to the request: runtime Java, Vulkan/Beryl code, shaders, Gradle/build logic, CI/workflows, packaging, documentation, tests, or repository maintenance.
- Make the smallest targeted change that satisfies the prompt, and avoid broad refactors unless explicitly requested.
- Preserve existing behavior unless the task asks for a behavior change.
- Runtime code, shaders, workflow files, Gradle files, packaging files, tests, diagnostics, and repository-maintenance files may be changed when they are directly relevant to the requested task.
- Do not remove MDIC/OpenGL reference backend code unless explicitly requested; it is kept intentionally as reference material during the Vulkan/Beryl port.
- Do not re-add Sodium as a hard runtime dependency or change Sodium runtime metadata/dependencies unless explicitly requested.
- Keep old tests, probes, diagnostic shaders, diagnostics, and environment flags unless explicitly asked to remove them; they are useful for regression checks and bisecting rendering/device-loss issues.

## Extra caution areas
- Release workflows, publishing configuration, versioning, license/notice files, and installer/package metadata can have downstream effects; change them only when the task directly calls for it.
- Prefer updating existing diagnostics or probe modes before adding new environment flags, shader probes, or one-off debug paths.
- When touching shaders or GPU-facing data layouts, verify matching Java-side structures, bindings, push constants, descriptor layouts, and generated resources where applicable.
- When touching CI or Gradle logic, keep local developer workflows and GitHub Actions behavior aligned where practical.

## Freebuff handoff
- Copy-paste snippets are delivered in chat, never embedded in plan files.
- Snippets must be branch-agnostic — no `git checkout`, `git pull`, or other branch-switching commands. Just point freebuff at the plan file, e.g.:
  `Read .opencode/plans/<plan>.md and implement the changes described there. When done run ./gradlew compileJava && git diff --check.`
- The user handles commit/stash on any conflicting uncommitted changes before invoking freebuff.
- Every plan's acceptance criteria must include jar rebuild and deploy: `[ ] jar rebuilt and deployed to PrismLauncher mods folder`.
- The user does NOT run rebuild/deploy themselves after freebuff — opencode does it automatically as the final acceptance step.

## Test & log workflow
- **Crash**: I read the crash report directly from the filesystem — user does nothing.
- **Runtime log** (no crash, need diagnostics): User enables PrismLauncher log-to-file via instance Settings → Logging → set path to `/tmp/voxy-lod.log`, runs game 30-60s, exits. I read and grep that file via a subagent.
- **Grep for LOD diagnostics** (done server-side by me, not user): `grep -E '\[Voxy\]\[VulkanBeryl\].*(lod-bringup-summary|lod-gated:|gpu-stage-gate|cmdgen-dispatch-gate-state|render-list-safety-state|render-list-population-state|render-list-readback-state|render-list-visibility-diagnostics|visible-count-zero-diagnostic|initial-traversal-dispatch-skipped|traversal-remaining-iterations-skipped|traversal-dispatch-status|cmdgen-renderlist-draw-handoff|cmdgen-dispatch-submitted|cmdgen-noop-dispatch-submitted)'`

## Build and deploy (done by opencode, not user)
- After every freebuff change, opencode runs the full build + deploy:
  ```
  cd /home/chasem/Projects/Vulkan-Voxy && ./gradlew build && cp build/libs/vulkan-voxy-*.jar "/home/chasem/.local/share/PrismLauncher/instances/Vulkan Optimized/minecraft/mods/"
  ```
- This applies to ALL changes — Java, shaders, resources — not just shader changes.

## Build and validation
- Prefer the smallest useful validation command for the files changed.
  - Java/runtime-only changes: prefer `./gradlew compileJava` unless tests or a broader build are directly relevant.
  - Shader/resource changes: run the narrowest Gradle task or resource validation that exercises the changed assets, if available.
  - Gradle, packaging, or workflow changes: run the most relevant Gradle validation locally when practical, and inspect the affected workflow/configuration carefully.
  - Documentation or metadata-only changes: `git diff --check` may be sufficient unless the task requires a generated output check.
- Always run `git diff --check` before handing off changes.
- Only run a full build when workflow, Gradle, packaging, resource, or cross-cutting runtime changes make it useful.
- Treat GitHub Actions as the source of truth when local toolchain state is missing, stale, or otherwise unreliable; call out any local environment limitation clearly.

## Output style
- Summarize the files changed.
- Explain why the change was made and how it addresses the request.
- List validation that was run.
- State the result of each validation command or check.
- Call out remaining risk, follow-up work, or the next most useful test when relevant.

## Cross-Session Memory
- `.opencode/context/activeContext.md` — current focus, read first
- `.opencode/context/decisionLog.md` — architectural decisions
- `.opencode/context/progress.md` — what's done and what's left

## Pre-Release Gates
- Semgrep scans in CI (Java security rules)
- NEVER commit secrets — checked manually in code review
- GPU synchronization correctness: always verify barriers between compute and graphics stages

## Spec Process
- Use `specs/SCOPE_TEMPLATE.md` for all feature specs
- Follow SCOPE method: Structure → Constraints → Outcomes → Phases → Examples
- Keep specs under 100 lines
- Always include "Out of Scope" section

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

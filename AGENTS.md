# AGENTS.md

## Project orientation
- Vulkan-Voxy is a fork of Voxy being ported to VulkanMod/Beryl.
- The default branch is `dev`.
- MDIC/OpenGL code is intentionally kept as reference code while Vulkan/Beryl work continues.

## Look here first
- Current high-priority area: Vulkan/Beryl cmdgen, drawCount, and device-loss debugging.
- Hot files for current work:
  - `src/main/java/me/cortex/voxy/client/core/rendering/section/backend/vulkanberyl/VulkanBerylSectionDrawPipeline.java`
  - `src/main/java/me/cortex/voxy/client/core/rendering/section/backend/vulkanberyl/`
  - `src/main/resources/assets/voxy/shaders/vulkanberyl/section/`

## Do not touch unless explicitly requested
- Docs and project metadata: `docs/`, `README*`, `INSTALL*`, `NOTICE*`, `LICENSE*`.
- Release workflows.
- OpenGL/MDIC reference backend code.
- Sodium runtime metadata or dependencies; do not re-add Sodium as a runtime dependency.
- Runtime code, shaders, workflows, old tests, probes, diagnostic shaders, or env flags unless the prompt asks for those changes.

## Build and validation
- Prefer `./gradlew compileJava` for focused Java/runtime changes.
- Always run `git diff --check` before handing off changes.
- Only run a full build when workflow, Gradle, or resource changes require it.

## Diagnostic policy
- Do not add new env vars or shader probes unless the prompt explicitly asks, or existing probes cannot answer the question.
- Prefer existing probe modes.
- Keep old probes; they are useful for bisecting device-loss issues.

## Output style
- Summarize changed files.
- Explain why the change follows from the latest runtime test.
- Provide the exact next runtime command or test when relevant.

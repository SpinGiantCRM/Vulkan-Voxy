# Claude project instructions

Read `AGENTS.md` first.

Additional focused context:
- Repo map: @docs/ai/repo-map.md
- Runtime debug workflow: @docs/ai/runtime-debug-playbook.md
- Prompt/output contract: @docs/ai/prompt-contract.md

Core rules:
- Default branch is `dev`.
- Make the smallest targeted change that satisfies the task.
- Do not remove MDIC/OpenGL reference backend code.
- Do not remove old tests, probes, diagnostics, diagnostic shaders, or env flags unless explicitly requested.
- Prefer updating existing diagnostics/probes before adding new env vars or shader variants.
- When touching Vulkan/shader/GPU-facing layout, verify Java-side bindings, descriptors, buffers, shader declarations, and barriers together.
- Always run `git diff --check`.
- Prefer `./gradlew compileJava` for Java/runtime changes.
- If Java/toolchain/network blocks validation, state that clearly instead of guessing.

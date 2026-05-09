# Prompt/Output Contract

Expected behavior for AI interactions with Vulkan-Voxy.

## Input Expectations

- Tasks should identify the relevant area: runtime Java, Vulkan/Beryl code, shaders, Gradle/build, CI/workflows, packaging, documentation, tests, or repo maintenance
- Explicitly request behavior changes; otherwise preserve existing behavior

## Output Requirements

1. **Summarize files changed** - List what was modified
2. **Explain why and how** - Address the request with minimal changes
3. **List validation run** - Include commands used
4. **State results** - Pass/fail for each check
5. **Call out blockers** - Java 25, toolchain, or network issues
6. **Call out remaining risk** - Follow-up work or next useful test

## Constraints

- **Do not** remove MDIC/OpenGL reference code (kept intentionally)
- **Do not** remove old tests, probes, diagnostics, or env flags
- **Do not** re-add Sodium as hard runtime dependency
- **Do not** change release workflows, versioning, or packaging metadata unless task explicitly requires it

## Validation Commands

| Change Type | Preferred Validation |
|-------------|---------------------|
| Java/runtime | `./gradlew compileJava` |
| Shaders/resources | Narrowest Gradle task exercising the asset |
| Gradle/workflows | Relevant local validation or careful inspection |
| Documentation only | `git diff --check` |

Always run `git diff --check` before handing off.

## When Blocked

If local validation fails due to:
- Java 25 toolchain issues
- Network blocks
- Missing dependencies

State clearly instead of guessing. Use GitHub Actions as source of truth when local state is unreliable.

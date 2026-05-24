# AGENTS.md

> Machine-readable instructions for AI coding agents (Claude Code, GitHub Copilot, Cursor, Windsurf, Mistral Vibe, etc.).
> Human contributors: see `README.md` and `CONTRIBUTING.md`.

---

## Project Overview

Kotlin/JVM backend service using Ktor.
All business logic is covered by unit and integration tests; smoke tests validate the running server.

---

## Environment

| Tool        | Version / Notes                            |
|-------------|--------------------------------------------|
| JDK         | 21 (Oracle / Temurin / Corretto)           |
| Kotlin      | 2.x (see `gradle/libs.versions.toml`)      |
| Gradle      | Wrapper — always use `./gradlew`           |
| ktlint      | via `org.jlleitschuh.gradle.ktlint` plugin |
| Test runner | Kotlin test + Ktor `testApplication`       |

---

## Build & Run

```bash
# Full build (compile + test + lint check)
./gradlew build

# Run the server locally (default port 8080)
./gradlew :server:run

# Run a specific subproject
./gradlew :<subproject>:build
```

---

## Code Style — ktlint

All Kotlin code must pass *ktlint* before committing.

### Check (lint only — does NOT modify files)

```bash
./gradlew ktlintCheck
```

Run this after every set of changes. A non-zero exit code means style violations exist.
Fix them manually or use the auto-formatter below.

### Format (auto-fix)

```bash
./gradlew ktlintFormat
```

Rewrites files in-place to comply with the Kotlin coding conventions enforced by ktlint.
Run `ktlintFormat` first, then re-run `ktlintCheck` to confirm zero violations.

### Rules

- Never disable ktlint rules with `@Suppress` or `// ktlint-disable` unless the suppression is strictly necessary and includes an explanatory comment.
- Configuration lives in `.editorconfig` at the repo root — do not override it per-file.
- Imports must be sorted; wildcard imports are forbidden.

---

## Testing

### Unit & Integration Tests

```bash
# Run all tests
./gradlew test

# Run tests for a specific subproject
./gradlew :<subproject>:test

# Run a single test class or method
./gradlew :<subproject>:test --tests "com.example.SomeTest"
./gradlew :<subproject>:test --tests "com.example.SomeTest.someMethod"
```

Tests live in `src/test/kotlin/`. Use `testApplication {}` from `ktor-server-test-host` for in-process HTTP tests.

### Smoke Tests

Smoke tests are *mandatory* when writing or modifying any:
- HTTP route / endpoint
- Public API surface
- Authentication / middleware layer
- Background job / scheduled task

Smoke tests live in `src/smokeTest/kotlin/` (or `smoke-tests/` module, depending on subproject).
They start an **embedded Ktor server** and fire real HTTP requests against it.

```bash
# Run smoke tests
./gradlew smokeTest

# Or directly via test task with the smokeTest source set
./gradlew :<subproject>:smokeTest
```

**When to add a smoke test:**

1. New route added → add a smoke test that calls the endpoint and asserts the response status + body shape.
2. Existing route changed → update the corresponding smoke test.
3. Auth middleware changed → add a smoke test for both authorized and unauthorized requests.
4. Service wired to a new dependency → add a smoke test that exercises the happy path end-to-end.

**Smoke test template (Ktor `testApplication`):**

```kotlin
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleSmokeTest {

    @Test
    fun `GET health returns 200`() = testApplication {
        application { module() }          // wire your Ktor application module
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `POST items returns 201 with created entity`() = testApplication {
        application { module() }
        val response = client.post("/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"test"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
    }
}
```

---

## Commit Requirements

- Commit summary: `<type>(<scope>): <subject>` — e.g., `feat(kurai-backend): add something`.
- Optional description as a bullet list; wrap lines as needed.
- Summary and description lines must not exceed 72 characters.
- `ktlintCheck` must pass locally before every commit.
- All existing tests must remain green.

---

## Project Structure (abbreviated)

```
.
├── AGENTS.md               ← you are here
├── build.gradle.kts        ← root Gradle config
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml  ← version catalog
├── server/                 ← Ktor server subproject
│   ├── src/main/kotlin/
│   ├── src/test/kotlin/      ← unit & integration tests
│   └── src/smokeTest/kotlin/ ← smoke tests
├── domain/                   ← business logic (pure Kotlin)
│   ├── src/main/kotlin/
│   └── src/test/kotlin/
└── ...                     ← other subprojects
```

Nested `AGENTS.md` files inside subproject directories take precedence over this root file for subproject-specific instructions.

---

## Conventions

- **Follow KISS, DRY, YAGNI** - keep it simple, avoid duplication, and only implement what is necessary.
- **Region tags** - use `// region` and `// endregion` to group related code blocks.
- **Suspend functions** for all I/O — never block a coroutine dispatcher thread.
- **Sealed classes** for results/errors — do not use raw exceptions as control flow.
- **Data classes** for DTOs — must be serializable with `kotlinx.serialization`.
- **Dependency injection** via constructor — no service locators or static state.
- **No magic numbers** — extract constants with descriptive names.
- **No `println`** — use the project's logger (`val log = LoggerFactory.getLogger(...)`).
- **No force-unwrapping** — avoid `!!` operator, handle nullability explicitly.

---

## What NOT to Do

- Do not run `gradle` directly — always use the wrapper `./gradlew`.
- Do not commit with failing tests or ktlint violations.
- Do not add new endpoints without a corresponding smoke test.
- Do not suppress ktlint without a comment explaining why.
- Do not modify `gradle/wrapper/` files unless explicitly asked.

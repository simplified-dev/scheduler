# Contributing

Thank you for your interest in contributing! This guide covers everything you need to get started.

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Development Setup](#development-setup)
- [Making Changes](#making-changes)
  - [Branching Strategy](#branching-strategy)
  - [Code Style](#code-style)
  - [Commit Messages](#commit-messages)
  - [Running Tests](#running-tests)
- [Submitting a Pull Request](#submitting-a-pull-request)
- [Reporting Issues](#reporting-issues)
- [Project Architecture](#project-architecture)
- [Legal](#legal)

## Getting Started

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| [Java JDK](https://adoptium.net/) | **21+** | Required (virtual threads, modern language features) |
| [Gradle](https://gradle.org/) | **9.4+** | Wrapper included - no manual install needed |
| [Git](https://git-scm.com/) | **2.x+** | Version control |

### Development Setup

1. **Fork** the repository on GitHub.

2. **Clone** your fork locally:
   ```bash
   git clone https://github.com/<your-username>/<repo>.git
   cd <repo>
   ```

3. **Build** the project to verify your setup:
   ```bash
   ./gradlew build
   ```

4. **Run tests** to confirm everything passes:
   ```bash
   ./gradlew test
   ```

> [!TIP]
> The Gradle wrapper (`./gradlew`) is checked into the repository, so you do not need to install Gradle separately. The wrapper will download the correct version automatically on first run.

## Making Changes

### Branching Strategy

- All development branches are created from `master`.
- Use descriptive branch names: `feature/add-widget`, `fix/null-pointer-in-parser`, `refactor/simplify-lookup`.

```bash
git checkout master
git pull origin master
git checkout -b feature/your-feature-name
```

### Code Style

- Follow standard **Java conventions** (Oracle/Google style).
- Use **Lombok** annotations where appropriate to reduce boilerplate (`@Getter`, `@RequiredArgsConstructor`, etc.).
- Use **JetBrains annotations** (`@NotNull`, `@Nullable`) on public API parameters and return types.
- Write **Javadoc** on all public classes and methods.
- Prefer immutability and thread-safe collections where applicable.
- Omit braces on single-line control flow bodies; use braces when the body wraps.

### Commit Messages

- Use the **imperative mood** in the subject line (e.g., "Add retry logic" not "Added retry logic").
- Keep the subject line under 72 characters.
- Optionally include a blank line followed by a longer description body.

```
Add configurable timeout for async tasks

The default timeout of 30 seconds was insufficient for long-running
downloads. This adds a constructor parameter to override it.
```

### Running Tests

```bash
# Standard unit tests
./gradlew test

# Full build (compile + test + checks)
./gradlew build
```

> [!NOTE]
> Some modules include additional test tasks (e.g., `slowTest` for integration tests). Check the module's `build.gradle.kts` for available tasks.

## Submitting a Pull Request

1. **Push** your branch to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```

2. **Open a pull request** against the `master` branch of the upstream repository.

3. In the PR description:
   - Summarize **what** you changed and **why**.
   - Reference any related issues (e.g., `Closes #42`).
   - Note any breaking changes or migration steps.

4. **Respond to review feedback** - maintainers may request changes before merging.

> [!IMPORTANT]
> All pull requests must target the `master` branch. Ensure your branch is up to date with `master` before submitting.

## Reporting Issues

- Use **GitHub Issues** to report bugs or request features.
- Include steps to reproduce, expected behavior, and actual behavior.
- Include your Java version (`java -version`) and OS when reporting bugs.

## Project Architecture

This project follows a standard Gradle single-module layout:

```
src/
├── main/java/       # Production source code
├── test/java/       # JUnit 5 test sources
└── jmh/java/        # JMH benchmark sources (if present)
```

- **Build configuration:** `build.gradle.kts` with a shared version catalog at `gradle/libs.versions.toml`.
- **Dependencies** are published via [JitPack](https://jitpack.io/) from the `master` branch.
- **Java 21** toolchain is enforced in the build script.

## Legal

By submitting a pull request, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE.md), the same license that covers this project.

# AO3 Reader

Personal Android AO3 reader for a Pixel 10 (Kotlin, Jetpack Compose).

## Resuming work
At the start of every session, read `docs/PROGRESS.md`. It names the active plan, the next task, and any open issues. Continue from there. After each completed task, update `docs/PROGRESS.md` (and tick the task's checkboxes in the plan) in the same commit.

## Rules
- No attribution in commits, PRs, or files: no `Co-Authored-By`, no "Generated with Claude" lines.
- Run Gradle with Android Studio's bundled JBR: `JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"` (JDK 25, Studio 2026.2 install; the old `Android Studio/jbr` is broken).
- Every AO3 request goes through `Ao3Fetcher`. Never add a second network path.

## Docs
- Spec: `docs/superpowers/specs/2026-11-20-ao3-reader-design.md`
- Plans: `docs/superpowers/plans/`

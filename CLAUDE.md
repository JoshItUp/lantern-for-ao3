# Lantern

Unofficial Android AO3 reader (Kotlin, Jetpack Compose), package `io.github.joshitup.lantern`, published as JoshItUp/lantern-for-ao3. See `README.md` for the features and architecture.

## Resuming work
At the start of every session, read `docs/PROGRESS.md`. It holds the current state, the open issue and the known follow-ups. Keep it current when work lands. `docs/` and `.superpowers/` are git-ignored and stay local, so never commit them.

## Rules
- No attribution in commits, PRs, or files: no `Co-Authored-By`, no "Generated with Claude" lines.
- Run Gradle with Android Studio's bundled JBR: `JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"` (JDK 25, Studio 2026.2 install; the old `Android Studio/jbr` is broken).
- Every AO3 request goes through `Ao3Fetcher`. Never add a second network path. Update traffic uses `UpdateClient` (its own OkHttp, GitHub only).
- Test fixtures must be synthetic. Never commit real AO3 data (usernames, work, bookmark or reading ids, titles).

## Docs (local)
- Specs: `docs/superpowers/specs/`
- Plans (historical): `docs/superpowers/plans/`

## Releasing
Actions → Release → Run workflow (patch, minor or major) from `main`. Hand-written notes go under `## [Unreleased]` in `CHANGELOG.md`. See the README for the required secrets and local release signing.

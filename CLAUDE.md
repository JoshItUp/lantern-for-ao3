# AO3 Reader

Personal Android AO3 reader for a Pixel 10 (Kotlin, Jetpack Compose). Plans 1–3 are built and merged; see `README.md` for the features and architecture.

## Resuming work
At the start of every session, read `docs/PROGRESS.md`. It holds the current state, the open issue and the known follow-ups. Keep it current when work lands. `docs/` and `.superpowers/` are git-ignored and stay local, so never commit them.

## Rules
- No attribution in commits, PRs, or files: no `Co-Authored-By`, no "Generated with Claude" lines.
- Run Gradle with Android Studio's bundled JBR: `JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr"` (JDK 25, Studio 2026.2 install; the old `Android Studio/jbr` is broken).
- Every AO3 request goes through `Ao3Fetcher`. Never add a second network path.
- Test fixtures must be synthetic. Never commit real AO3 data (usernames, work, bookmark or reading ids, titles).

## Docs (local)
- Specs: `docs/superpowers/specs/`
- Plans (historical): `docs/superpowers/plans/`

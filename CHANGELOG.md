# Changelog

All notable changes to Lantern are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.2] - 2026-11-23

### Changed
- The README's install steps now explain the Google Play Protect warning. Nothing changes in the app; this release tests silent in-app updates.

## [1.0.1] - 2026-11-23

### Changed
- Internal tidy-up of the updater's states. Nothing changes in use; this release exists to test in-app updating from 1.0.0.

## [1.0.0] - 2026-11-23

The first public release of Lantern, an unofficial Android reader for Archive of Our Own.

### Added
- Search with AO3's full work search: any field, title and creator; fandoms, characters, relationships, additional tags and tags to exclude, with AO3's autocomplete; rating, warnings, categories, completion, crossovers, word count, language, updated date and sort order.
- Saved search presets shown as chips; long-press one to rename, update, reorder or delete it.
- Result cards with every tag and the full summary, 20 per page, and a Continue strip that reopens the last work you read.
- A native reader with one chapter per scroll, swiping between chapters, a chapter list, and resume at the exact chapter and position.
- Immersive reading (tap the centre to show or hide the controls), font size and font family (system, serif, sans or mono), and the next chapter prefetched in the background.
- Sign-in with your AO3 username or email; credentials are encrypted with an Android Keystore key so the app signs in again quietly when the session expires. Searching and reading work signed out.
- A Library with History, Marked for Later, Bookmarks, Subscriptions and Downloads, and "Read" / "✓ Ch N" badges on works you have read.
- Account actions from the reader: kudos, bookmarks (notes, tags, private, rec), mark for later, subscribe and comment. Chapters read while signed in count in your AO3 History.
- Offline downloads of whole works, read in the same reader. Downloads run in the background, survive sign-out and notice when AO3 posts new chapters.
- Author and series pages opened in the app, with Subscribe in the header.
- In-app updates: a daily check for new releases, the changelog shown before you update, and a verified download handed to Android's installer, with "What's new" after the update and a manual check in About.
- Polite AO3 traffic: one request at a time about a second apart, `Retry-After` honoured, and a Cloudflare challenge solved once in a WebView when AO3 asks for one.

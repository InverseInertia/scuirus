# Pantry

An Android app for tracking groceries so less food goes bad. You add stock by barcode scan or receipt photo, remove it by barcode scan or one-tap buttons, and every change goes into a git repository that syncs through a server you run.

Status: the `core` module is tested. The `app` module has never been compiled. Read "What was verified" before trusting anything in it.

## Layout

```
core/   Pure Kotlin (JVM). Fractions, event replay, JSON Lines codec, receipt parsing, git workflow rules. 45 tests.
app/    Android. CameraX, ML Kit, JGit, Jetpack Compose. Depends on core.
```

## Data in the git repository

```
catalog.jsonl                     one product per line, sorted by id
aliases.jsonl                     one receipt-text alias per line, learned from your corrections
events/<device>/<yyyy-MM>.jsonl   append-only history, one JSON object per line
receipts/<yyyy-MM>/<id>.txt       raw OCR text of each scanned receipt
.pantry-local/                    git-ignored, holds pending change summaries for commit messages
```

Current stock is not stored. The app rebuilds it by replaying the events, so it can never disagree with the history.

Event types are `add`, `consume`, `discard`, `adjust`, and `void`. A `consume` or `discard` without a lot id takes from the earliest-expiring lots first and spans lots when needed. Quantities are exact fractions written as text (`"1/3"`, `"2"`), so three removals of a third empty a lot exactly.

Each device writes only to its own event files. Two phones that work offline and then sync never edit the same event file, so git merges them without conflicts. Replay sorts by timestamp and event id, so both phones end up with identical stock after pulling.

Every data file is JSON Lines: one flat object per line, every value a string. A barcode is stored as `"012345678905"`, so the leading zero survives. If you edit the catalog by hand and write a bare number, the app refuses to load it and names the file and line, rather than quietly turning `012345` into `12`. A bad line in the catalog stops the load on purpose, because skipping it would delete that product the next time the file is saved. Bad lines in event files are skipped and listed on the Changes tab.

## The commit rule

Every operation records a one-line summary for the next commit message. The Changes tab shows uncommitted files, a pre-filled message, Commit, and Discard. Sync is disabled while anything is uncommitted. Sync fetches, rebases your commits onto the server's, and pushes. If the rebase conflicts, it aborts and leaves your files untouched.

## Building

Open the project in Android Studio (JDK 17). There is no Gradle wrapper in this archive because it could not be generated offline. Android Studio creates one, or run `gradle wrapper --gradle-version 8.9` once.

Run the core tests with `./gradlew :core:test`. Tests that need real git skip themselves silently when `git` is not on the path.

Before building, set your contact address for the Open Food Facts User-Agent (they ask API users to identify themselves). Add `off.contact=you@example.com` to `local.properties` (gitignored), or pass `-Poff.contact=you@example.com`, or set the `OFF_CONTACT` environment variable. The build fails with instructions if it's missing.

## Server

Any git host that speaks HTTPS works. The simplest is a bare repository behind Gitea or Forgejo. Create an access token with write access to that one repository and enter the HTTPS URL, username, and token in Settings. The token is stored with Android's Keystore-backed encrypted preferences.

## What was verified

- The 45 core tests pass. They ran under `kotlinc` 2.0.21 with a small JUnit-compatible runner, not under Gradle.
- The git workflow tests use the real `git` command line: a bare remote, two clones, offline work on both, rebase, push, and identical stock on both sides afterwards. They also cover the empty-remote first push, discard, history, and revert.
- Catalog round trips keep barcodes with leading zeros, and hand-edited files with bare numbers or broken lines are rejected with a line number.

## What was not verified

- The `app` module. No Android SDK or Maven access was available, so nothing in `app/` has been compiled or run. Expect compile errors to fix on first build, especially around CameraX, Compose, and JGit API details.
- `JGitVersionControl`. It mirrors the `CliGit` class the tests exercise, but it has never run. Revert conflict cleanup and rebase conflict reporting are the least certain parts.
- The Open Food Facts URL and field names. They come from memory of the v2 API, and the parser was tested against a hand-written sample reply, not a live one.
- The receipt parser against real receipts. It was tested on one synthetic receipt. Expect to tune the skip words, price pattern, and abbreviation table against your own stores.

## Known gaps

- `catalog.jsonl` can conflict if two devices edit the same product while offline. Sync aborts and names the file. There is no in-app resolution yet.
- Revert works cleanly for recent commits. Reverting an older commit fails if later commits appended to the same event file.
- An `adjust` event can set a new expiry but cannot clear one.
- Removing more than is recorded produces a warning on the Changes tab instead of negative stock.
- Receipt photos are not kept, only the OCR text.
- Authentication is an HTTPS token only. JGit's SSH support on Android was left out on purpose.
- ML Kit is free and runs on the phone, but it is not open source. That blocks F-Droid distribution. The scanner and recognizer are each one function in `scan/Camera.kt` if you want to swap in zxing-cpp or Tesseract.
- Stats (average unit price, usage per day, days of stock, waste share) show on each product card. There is no trends screen.

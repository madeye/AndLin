# Contributing to ServerBox
Thanks for helping improve ServerBox! These are guidelines, not rules, so use your best judgement.

All contributions must follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Connect with us
Talk with us through an issue or a pull request.

## Architecture
ServerBox follows the MVVM-C architecture. UI lives in XML and is inflated only by
view controllers (activities and fragments). Business logic should be decoupled from the Android
framework as much as possible and live in the `model` or `utils` packages. Application-layer logic
belongs in the view models.

The modules are:
- `app`: the application shell and per-distribution assets (the Apps list lives in
  `app/src/ServerBox/assets/apps/apps.txt`).
- `CustomLibrary`: the `ServerBox` build flavor's configuration (BuildConfig defaults such as the
  default user and image tag).
- `library`: sessions, filesystems, the PRoot and OCI setup, the SSH server and settings.
- `terminal`: the built-in terminal (an SSH client to the session's server).

The distribution images come from [madeye/ServerBox-Images](https://github.com/madeye/ServerBox-Images).
Room schema changes need a new database version, a migration, and the exported schema JSON under
`library/schemas/`.

## Steps to follow
1. Open an issue describing the problem your contribution solves, if there isn't one yet.
2. Branch from `master`.
3. Write your code and tests for it.
4. Run the tests and lint (Android builds need JDK 17):
   `./gradlew testServerBoxDebugUnitTest :terminal:testDebugUnitTest lintServerBoxDebug`.
   If you touched the database or the terminal, also run the instrumented tests on a device or
   emulator (see the README).
5. Open a pull request against `master`.

## Style guides
**Commit messages**
- Use present tense.
- Keep the first line short; reference issues and add detail in the lines after.

**Branch names**
- Describe the change briefly, e.g. `fix/session-start` or `feature/server-info`.

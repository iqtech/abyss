# Abyss

Don't commit or push until explicitly asked.

## Versioning

Version format: `major.minor.patch`

- Default bump: increment **patch** by 1, no upper bound.
- `minor` bump (explicit only): increment minor by 1, reset patch to 0.
- `major` bump (explicit only): increment major by 1, reset minor and patch to 0.

Version is set in `build.gradle.kts` → `allprojects { version = "..." }`.

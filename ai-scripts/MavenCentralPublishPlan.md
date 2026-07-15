# Plan: Publish Abyss to Maven Central

## Context

Abyss is a Kotlin JVM library (4 submodules, group `pl.iqtech.abyss`, version `0.17.0`) backed by Hazelcast with a pluggable storage layer. The goal is to publish all four artifacts to Maven Central so consumers can use `mavenCentral()` with no extra setup.

`maven-publish` is already applied but has no remote repository target, no POM metadata, no sources/javadoc JARs, and no GPG signing. None of these are optional for Maven Central.

---

## What's Missing (in order)

| # | Item | Status          |
|---|---|-----------------|
| 1 | LICENSE file | ✅ Done          |
| 2 | Sonatype Central Portal account | ✅ Done          |
| 3 | Namespace verification (`pl.iqtech`) | ✅ Done                |
| 4 | GPG signing key | Not created     |
| 5 | POM metadata + sources + javadoc | Not configured  |
| 6 | Publishing repository (Central Portal) | Not configured  |
| 7 | GitHub Actions CI/CD workflow | No .github/ dir |
| 8 | GitHub repo secrets | Not configured  |

---

## Implementation Plan

### ~~Step 1 — Add LICENSE file~~ ✅ Done

### Step 2 — Sonatype Central Portal account

Manual prerequisite (one-time, done by user):
1. Register at https://central.sonatype.com
2. Verify namespace ownership for `pl.iqtech`:
   - **Option A (DNS — keep current groupId):** Add a TXT record to `iqtech.pl` domain: `txt OSSRH-<your-token>`. This proves ownership of `pl.iqtech.*`.
   - **Option B (GitHub — change groupId):** Use `io.github.iqtech` as groupId. Sonatype auto-verifies via GitHub org membership. Requires changing `group` in `build.gradle.kts`.

   Recommendation: Option A if the `iqtech.pl` domain is controlled; Option B if not.

3. Generate a deployment token in the portal (used as credentials for publishing).

### Step 3 — Generate GPG key

```bash
gpg --gen-key  # use name + iqtechpl@gmail.com
gpg --list-secret-keys --keyid-format=long
gpg --export-secret-keys --armor <KEY_ID> > private.key
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Save: `KEY_ID`, the ASCII-armored private key, and the passphrase — these become GitHub secrets in Step 6.

### Step 4 — Switch to `com.vanniktech.maven.publish` plugin

Replace the manual `maven-publish` + `MavenPublication` setup in `build.gradle.kts` with the Vanniktech plugin, which handles POM metadata, sources JAR, javadoc JAR, GPG signing, and Central Portal bundle upload (the new portal uses a ZIP API, not standard Maven deploy).

**Root `build.gradle.kts` changes:**

Add to `plugins` block:
```kotlin
id("com.vanniktech.maven.publish") version "0.30.0" apply false
```

Replace the existing `subprojects { configure<PublishingExtension> { ... } }` block with:
```kotlin
subprojects {
    apply(plugin = "com.vanniktech.maven.publish")

    mavenPublishing {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
        signAllPublications()

        coordinates(
            groupId = project.group.toString(),
            artifactId = project.name,
            version = project.version.toString()
        )

        pom {
            name.set(project.name)
            description.set("In-memory property graph engine backed by Hazelcast with pluggable durable storage")
            url.set("https://github.com/iqtech/abyss")
            licenses {
                license {
                    name.set("Apache-2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            developers {
                developer {
                    id.set("iqtech")
                    email.set("iqtechpl@gmail.com")
                }
            }
            scm {
                url.set("https://github.com/iqtech/abyss")
                connection.set("scm:git:git://github.com/iqtech/abyss.git")
                developerConnection.set("scm:git:ssh://git@github.com/iqtech/abyss.git")
            }
        }
    }
}
```

Remove the old `configure<PublishingExtension>` block from `subprojects`.

### Step 5 — GitHub Actions workflow

Create `.github/workflows/publish.yml`. Triggers on push of a `v*` tag (e.g. `v0.17.0`).

```yaml
name: Publish to Maven Central
on:
  push:
    tags: ["v*"]
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: 21
          distribution: temurin
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew publish
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.GPG_PRIVATE_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.GPG_PASSPHRASE }}
```

### Step 6 — Configure GitHub repo secrets

In `iqtech/abyss` repo → Settings → Secrets and variables → Actions:

| Secret name | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Sonatype Central Portal deployment token username |
| `MAVEN_CENTRAL_PASSWORD` | Sonatype Central Portal deployment token password |
| `GPG_PRIVATE_KEY` | ASCII-armored private key from Step 3 |
| `GPG_PASSPHRASE` | GPG key passphrase from Step 3 |

---

## Files to create/modify

| File | Action |
|---|---|
| `LICENSE` | ✅ Done |
| `build.gradle.kts` | Modify — replace `maven-publish` block with Vanniktech plugin |
| `.github/workflows/publish.yml` | Create |

---

## Verification

1. **Local smoke test:** `./gradlew publishToMavenLocal` — verify it still works after the Vanniktech switch.
2. **Signing test:** `./gradlew publish --dry-run` with local GPG key set via `gradle.properties`.
3. **End-to-end:** Push a `v0.17.0` tag → watch GitHub Actions → confirm artifacts appear in https://central.sonatype.com under `pl.iqtech.abyss`.

---

## Manual prerequisites (user must complete before CI can publish)

- [x] Register at https://central.sonatype.com
- [ ] Verify `pl.iqtech` namespace (DNS TXT on iqtech.pl) — or decide to switch to `io.github.iqtech`
- [ ] Generate deployment token in Central Portal
- [ ] Generate GPG key and upload public key to keyserver.ubuntu.com
- [ ] Add 4 secrets to GitHub repo settings (see Step 6)

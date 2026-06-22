# contributing

## layout

```
app/                kotlin android app (the accessibility service + receiver)
  src/main/kotlin/com/khimaros/mimic/
  src/main/res/
  src/main/AndroidManifest.xml
cli/mimic            posix shell cli for termux / adb
tests/              python end-to-end tests (driven over adb)
SKILL.md            intent + cli reference for agents
Makefile            build / install / test entry points
mise.toml           pinned toolchain
```

## toolchain

`mise` pins the jdk and gradle. install them with:

```
mise install
```

the android sdk platform and build-tools are large and license-gated, so they
are not vendored. `mise` defaults `ANDROID_HOME` to `~/android-sdk`; install the
needed packages there (and accept licenses) once:

```
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
sdkmanager --licenses
```

if your sdk lives elsewhere, export a different `ANDROID_HOME`, or set `sdk.dir`
in a `local.properties` (gitignored, takes precedence). no machine-specific paths
are checked into the repo.

## build and test

```
make            # assemble debug apk
make install    # adb install onto a connected device/emulator
make test-e2e   # python e2e over adb (skips when no device attached)
make precommit  # run before committing: lint + build + e2e
make release    # assemble release apk (see signing below)
```

## release

`make release` runs `assembleRelease`; output is under
`app/build/outputs/apk/release/`. signing is env-gated, and the keystore is never
checked into the repo:

- with no env set, you get `app-release-unsigned.apk` (sign it yourself with
  `zipalign` + `apksigner`).
- set the signing env to get a signed `app-release.apk` directly:

  ```
  # one-time: create a keystore kept outside the repo
  keytool -genkeypair -v -keystore ~/.android/mimic-release.jks -alias mimic \
      -keyalg RSA -keysize 2048 -validity 10000

  MIMIC_KEYSTORE=~/.android/mimic-release.jks MIMIC_KEYSTORE_PASS=... \
  MIMIC_KEY_ALIAS=mimic MIMIC_KEY_PASS=... make release
  ```

  updates to an installed copy must use the same key (android rejects a re-sign
  with a different key unless you uninstall first).

## tests

prefer end-to-end integration tests over unit tests. e2e tests live in `tests/`,
are written in python, and are managed with `uv`. they install the apk, enable
the service, pair, and exercise the intent protocol against a real device or
emulator over `adb`. when no device is attached they skip rather than fail.

## workflow

- add a task to [ROADMAP.md](ROADMAP.md) before starting it; mark it done after.
- update [DESIGN.md](DESIGN.md) after architectural changes and
  [README.md](README.md) / [SKILL.md](SKILL.md) after user-visible changes.
- never regress a requirement in [REQUIREMENTS.md](REQUIREMENTS.md).
- magical constants live as named values at the top of the file that uses them,
  or in the shared protocol file (`Protocol.kt`: `Cmd`/`Actions`/`Extras`/`Defaults`).
- keep dependencies minimal. ascii only. lowercase docs and output.
- version control is the maintainer's job; do not commit, tag, or push.

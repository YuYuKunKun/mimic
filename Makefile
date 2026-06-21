# prefer the gradle wrapper; else the mise-pinned gradle; else a system gradle.
GRADLE := $(if $(wildcard ./gradlew),./gradlew,$(if $(shell command -v mise),mise exec -- gradle,gradle))

.PHONY: all build release install uninstall test-e2e precommit lint clean wrapper

all: build

build:
	$(GRADLE) assembleDebug

# assemble the release apk. signed if MIMIC_KEYSTORE (+ MIMIC_KEYSTORE_PASS /
# MIMIC_KEY_ALIAS / MIMIC_KEY_PASS) is set, otherwise unsigned. output is under
# app/build/outputs/apk/release/.
release:
	$(GRADLE) assembleRelease

install:
	$(GRADLE) installDebug

uninstall:
	adb uninstall com.khimaros.mimic

lint:
	$(GRADLE) lintDebug

# end-to-end tests drive a real device/emulator over adb and skip when none is
# attached. they install the freshly built debug apk themselves.
test-e2e: build
	cd tests && uv run pytest -v

precommit: lint build test-e2e

clean:
	$(GRADLE) clean

# regenerate the gradle wrapper jar (needs a system/mise gradle once).
wrapper:
	gradle wrapper --gradle-version 8.7

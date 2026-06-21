plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.khimaros.a11y"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.khimaros.a11y"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
    }

    // env-gated release signing; the keystore stays out of the repo. set
    // A11Y_KEYSTORE (+ A11Y_KEYSTORE_PASS / A11Y_KEY_ALIAS / A11Y_KEY_PASS) to
    // produce a signed release apk; without it, assembleRelease stays unsigned.
    // providers.environmentVariable reads the invoking env even under the daemon.
    fun env(name: String): String? = providers.environmentVariable(name).orNull
    val releaseSigning = env("A11Y_KEYSTORE")?.let { path ->
        val keystore = file(path)
        if (!keystore.exists()) null
        else signingConfigs.create("release") {
            storeFile = keystore
            storePassword = env("A11Y_KEYSTORE_PASS")
            keyAlias = env("A11Y_KEY_ALIAS")
            keyPassword = env("A11Y_KEY_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // expose versionName to code via BuildConfig.VERSION_NAME so the version has
    // a single source of truth (defaultConfig above).
    buildFeatures {
        buildConfig = true
    }
}

// no external dependencies: json uses the built-in org.json, auth uses
// android.util.Base64 + java.security, and the ui uses the platform framework
// directly. this also keeps the merged manifest to just our own components.
dependencies {
}

// bundle the cli and skill into assets so the host server can serve them for
// bootstrapping/updates. copied from the repo root at build time to stay in sync.
val bootstrapDir = layout.buildDirectory.dir("generated/bootstrap")
val syncBootstrap by tasks.registering(Copy::class) {
    from(rootProject.file("cli/a11y"))
    from(rootProject.file("SKILL.md"))
    into(bootstrapDir)
}
android.sourceSets.getByName("main").assets.srcDir(bootstrapDir)
tasks.named("preBuild") { dependsOn(syncBootstrap) }

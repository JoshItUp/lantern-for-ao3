import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

// Version: -Plantern.version=X.Y.Z, else the latest v* git tag, else 0.0.0-dev (versionCode 1).
val (lanternVersionName, lanternVersionCode) = run {
    val versionRegex = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
    fun codeOf(v: String): Int? {
        val m = versionRegex.matchEntire(v) ?: return null
        val (major, minor, patch) = m.destructured
        val mi = minor.toIntOrNull() ?: return null
        val pa = patch.toIntOrNull() ?: return null
        val ma = major.toIntOrNull() ?: return null
        if (mi >= 100 || pa >= 100) return null
        return ma * 10000 + mi * 100 + pa
    }
    val explicit = providers.gradleProperty("lantern.version").orNull
    val fromGit = if (explicit == null) {
        try {
            val result = providers.exec {
                commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v*")
                workingDir = rootDir
                isIgnoreExitValue = true
            }
            val out = result.standardOutput.asText.get().trim()
            if (result.result.get().exitValue == 0 && out.isNotEmpty()) out.removePrefix("v") else null
        } catch (e: Exception) {
            null // git missing, or not a repository
        }
    } else null
    val chosen = explicit ?: fromGit
    if (chosen == null) {
        "0.0.0-dev" to 1
    } else {
        val code = codeOf(chosen)
        if (code == null) {
            throw GradleException(
                if (explicit != null) "Invalid -Plantern.version='$chosen': expected X.Y.Z with minor and patch below 100."
                else "Latest git tag 'v$chosen' is not X.Y.Z with minor and patch below 100; fix the tag or pass -Plantern.version."
            )
        }
        chosen to code
    }
}

// Release signing: LANTERN_* env vars (CI), else keystore.properties, else the debug key.
val signingEnvNames = listOf("LANTERN_KEYSTORE_FILE", "LANTERN_KEYSTORE_PASSWORD", "LANTERN_KEY_ALIAS", "LANTERN_KEY_PASSWORD")
val releaseKeystore: Map<String, String>? = run {
    if (providers.environmentVariable("LANTERN_KEYSTORE_FILE").isPresent) {
        val missing = signingEnvNames.filter { !providers.environmentVariable(it).isPresent }
        if (missing.isNotEmpty()) {
            throw GradleException("LANTERN_KEYSTORE_FILE is set but these signing env vars are missing: ${missing.joinToString()}")
        }
        mapOf(
            "storeFile" to providers.environmentVariable("LANTERN_KEYSTORE_FILE").get(),
            "storePassword" to providers.environmentVariable("LANTERN_KEYSTORE_PASSWORD").get(),
            "keyAlias" to providers.environmentVariable("LANTERN_KEY_ALIAS").get(),
            "keyPassword" to providers.environmentVariable("LANTERN_KEY_PASSWORD").get(),
        )
    } else {
        val propsPath = providers.gradleProperty("lantern.keystoreProps").orNull
            ?: "${System.getProperty("user.home")}/.lantern/keystore.properties"
        val propsFile = file(propsPath)
        if (propsFile.isFile) {
            val props = Properties().apply { propsFile.inputStream().use { load(it) } }
            val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            val missing = keys.filter { props.getProperty(it).isNullOrBlank() }
            if (missing.isNotEmpty()) throw GradleException("$propsPath is missing keys: ${missing.joinToString()}")
            keys.associateWith { props.getProperty(it) }
        } else null
    }
}

if (releaseKeystore == null) {
    // Warn only when a release variant is actually in the task graph.
    gradle.taskGraph.whenReady {
        val releaseTask = Regex("^(assemble|bundle|package)Release$")
        if (allTasks.any { it.project == project && releaseTask.matches(it.name) }) {
            logger.warn("Lantern: release build signed with the DEBUG key \u2014 it cannot update a release install")
        }
    }
}

android {
    namespace = "io.github.joshitup.lantern"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.joshitup.lantern"
        minSdk = 36
        targetSdk = 36
        versionCode = lanternVersionCode
        versionName = lanternVersionName

        // The GitHub repo the in-app update check reads releases from.
        buildConfigField("String", "UPDATE_REPO", "\"JoshItUp/lantern-for-ao3\"")
        // Lowercase hex SHA-256 of the release signing cert; empty means trust the installed app's own certificate.
        val certSha256 = providers.gradleProperty("lantern.certSha256").getOrElse("")
        if (certSha256.isNotEmpty() && !Regex("^[0-9a-f]{64}$").matches(certSha256)) {
            throw GradleException("Invalid -Plantern.certSha256: expected empty or 64 lowercase hex characters.")
        }
        buildConfigField("String", "SIGNING_CERT_SHA256", "\"$certSha256\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("lanternRelease") {
                storeFile = file(releaseKeystore.getValue("storeFile"))
                storePassword = releaseKeystore.getValue("storePassword")
                keyAlias = releaseKeystore.getValue("keyAlias")
                keyPassword = releaseKeystore.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "lanternRelease" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        // AGP 9 turns BuildConfig off by default; the update check needs it.
        buildConfig = true
    }

    // MigrationTestHelper (Robolectric) reads the exported schema JSON from the merged assets; debug only, so release APKs do not carry them.
    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric on JDK 21 needs access to JDK internals.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
}

room {
    // Room writes each schema version here; the JSON is committed so future migrations can be checked against it.
    schemaDirectory("$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

dependencies {
    implementation(project(":ao3"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jsoup)

    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

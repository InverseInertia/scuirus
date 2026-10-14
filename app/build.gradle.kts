plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Contact address sent in the Open Food Facts User-Agent. Kept out of the repo: set `off.contact` in
// local.yaml in the repo root (gitignored; copy local.example.yaml), or pass -Poff.contact=... / set OFF_CONTACT.
val offContact: String? = run {
    val yamlValue = rootProject.file("local.yaml").takeIf { it.exists() }?.readLines()
        ?.firstNotNullOfOrNull { Regex("""^\s*off_contact\s*:\s*(.*?)\s*(#.*)?$""").find(it)?.groupValues?.get(1) }
        ?.trim('"', '\'')
    (yamlValue?.takeIf { it.isNotEmpty() } ?: providers.gradleProperty("off.contact").orNull ?: System.getenv("OFF_CONTACT"))
        ?.trim()?.takeIf { it.isNotEmpty() }
}

android {
    namespace = "pantry.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "pantry.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val escaped = (offContact ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "OFF_CONTACT", "\"$escaped\"")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // JGit and its dependencies ship duplicate metadata files.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1",
            )
        }
    }
}

// Fail real builds (not Gradle sync or clean) when the contact address is missing.
tasks.matching { it.name.startsWith("generate") && it.name.endsWith("BuildConfig") }.configureEach {
    doFirst {
        if (offContact == null) {
            throw GradleException(
                "Open Food Facts contact address not set. Copy local.example.yaml to local.yaml in the repo root and set off_contact, " +
                    "or pass -Poff.contact=you@example.com, or set the OFF_CONTACT environment variable."
            )
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Camera and on-device recognition. The bundled ML Kit models work without Google Play services.
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // 5.13 is the last JGit line that targets Java 8, which Android handles without extra desugaring.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

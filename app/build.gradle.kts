import java.io.File
import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.gms.google-services") apply false
}

fun Project.resolveSigningProperty(name: String): String? {
    val fromGradle = providers.gradleProperty(name).orNull
    if (!fromGradle.isNullOrBlank()) return fromGradle
    val fromEnv = System.getenv(name)
    return if (fromEnv.isNullOrBlank()) null else fromEnv
}

fun parseVersionCodeFromName(versionName: String): Int {
    val trimmed = versionName.trim()
    val match = Regex("""^v(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$""").matchEntire(trimmed)
        ?: error("appVersionName must follow vX.Y.Z or vX.Y.Z-beta.N, got: $trimmed")

    val major = match.groupValues[1].toInt()
    val minor = match.groupValues[2].toInt()
    val patch = match.groupValues[3].toInt()
    val betaPart = match.groupValues[4]

    require(minor in 0..99) { "Minor version must be in 0..99, got: $minor ($trimmed)" }
    require(patch in 0..99) { "Patch version must be in 0..99, got: $patch ($trimmed)" }

    val suffix = if (betaPart.isEmpty()) {
        99
    } else {
        val beta = betaPart.toInt()
        require(beta in 1..98) {
            "Beta build number must be in 1..98, got: $beta ($trimmed)"
        }
        beta
    }

    return major * 1_000_000 + minor * 10_000 + patch * 100 + suffix
}

val releaseStoreFile = project.resolveSigningProperty("PUSHGO_RELEASE_STORE_FILE")
val releaseStorePassword = project.resolveSigningProperty("PUSHGO_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = project.resolveSigningProperty("PUSHGO_RELEASE_KEY_ALIAS")
val releaseKeyPassword = project.resolveSigningProperty("PUSHGO_RELEASE_KEY_PASSWORD")
val appVersionName = providers.gradleProperty("pushgo.versionName").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: "v1.2.0-beta.2"
val appVersionCode = parseVersionCodeFromName(appVersionName)
val enableAbiSplits = when (val value = providers.gradleProperty("pushgo.enableAbiSplits").orNull?.trim()?.lowercase()) {
    null -> true
    "true" -> true
    "false" -> false
    else -> error("Invalid pushgo.enableAbiSplits value: $value")
}
val rustBuildScript: File = rootProject.file("native/quinn-jni/build-android.sh")
val generatedRustJniDir: File = layout.buildDirectory.dir("generated/rustJniLibs/main").get().asFile
val privateCertPinSha256 = project.resolveSigningProperty("PUSHGO_PRIVATE_CERT_PIN_SHA256")
    ?.trim()
    ?.replace("\"", "")
    ?: ""
val updateFeedUrl = project.resolveSigningProperty("PUSHGO_UPDATE_FEED_URL")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: "https://update.pushgo.cn/android/update-feed-v1.json"
val updateFeedPublicKeyB64 = project.resolveSigningProperty("PUSHGO_UPDATE_FEED_PUBLIC_KEY_B64")
    ?.trim()
    ?.replace("\"", "")
    ?: ""
val updateFeedEcdsaP256PublicKeyB64 = project.resolveSigningProperty("PUSHGO_UPDATE_FEED_ECDSA_P256_PUBLIC_KEY_B64")
    ?.trim()
    ?.replace("\"", "")
    ?: ""

val buildRustJniLibs by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the Rust JNI libraries used by Android packaging."
    workingDir = rustBuildScript.parentFile
    commandLine("bash", rustBuildScript.absolutePath)
    environment("PUSHGO_ANDROID_JNI_OUT_DIR", generatedRustJniDir.absolutePath)
    inputs.file(rustBuildScript)
    listOf(
        rootProject.file("native/quinn-jni/Cargo.toml"),
        rootProject.file("native/quinn-jni/Cargo.lock"),
    ).filter(File::exists).forEach(inputs::file)
    listOf(
        rootProject.file("native/quinn-jni/src"),
        rootProject.file("native/quinn-jni/include"),
    ).filter(File::exists).forEach(inputs::dir)
    outputs.dir(generatedRustJniDir)
}

android {
    namespace = "io.ethan.pushgo"
    compileSdk = 36

    val releaseSigningConfig = if (
        !releaseStoreFile.isNullOrBlank()
        && !releaseStorePassword.isNullOrBlank()
        && !releaseKeyAlias.isNullOrBlank()
        && !releaseKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("release") {
            storeFile = File(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    } else {
        null
    }

    defaultConfig {
        applicationId = "io.ethan.pushgo"
        minSdk = 31
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PRIVATE_CERT_PIN_SHA256", "\"$privateCertPinSha256\"")
        buildConfigField("String", "DEFAULT_UPDATE_FEED_URL", "\"$updateFeedUrl\"")
        buildConfigField("String", "UPDATE_FEED_PUBLIC_KEY_B64", "\"$updateFeedPublicKeyB64\"")
        buildConfigField("String", "UPDATE_FEED_ECDSA_P256_PUBLIC_KEY_B64", "\"$updateFeedEcdsaP256PublicKeyB64\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEFAULT_SERVER_ADDRESS", "\"https://gateway.pushgo.cn\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "DEFAULT_SERVER_ADDRESS", "\"https://gateway.pushgo.cn\"")
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }

    splits {
        abi {
            isEnable = enableAbiSplits
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = enableAbiSplits
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(generatedRustJniDir.absolutePath)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildRustJniLibs)
}

tasks.register("printReleaseVersionInfo") {
    group = "help"
    description = "Prints the Android release version metadata for CI."
    doLast {
        println("versionName=$appVersionName")
        println("versionCode=$appVersionCode")
        println("applicationId=io.ethan.pushgo")
        println("abiSplitsEnabled=$enableAbiSplits")
    }
}

// APK-only distribution policy: disable release AAB tasks to avoid accidental bundle publishing.
tasks.configureEach {
    if (name in setOf(
            "buildReleasePreBundle",
            "bundleRelease",
            "packageReleaseBundle",
            "signReleaseBundle",
            "produceReleaseBundleIdeListingFile",
            "createReleaseBundleListingFileRedirect",
        )
    ) {
        enabled = false
    }
}

val hasGoogleServices = listOf(
    file("google-services.json"),
    file("src/google-services.json"),
    file("src/debug/google-services.json"),
    file("src/release/google-services.json"),
).any { it.exists() }

if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.7")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-paging:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.paging:paging-runtime-ktx:3.4.2")
    implementation("androidx.paging:paging-compose:3.4.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:image-coil:4.6.2")
    implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.android.gms:play-services-base:18.10.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    kspAndroidTest("androidx.room:room-compiler:2.8.4")

}

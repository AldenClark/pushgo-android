import org.gradle.api.attributes.java.TargetJvmEnvironment

plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "io.ethan.pushgo.macrobenchmark"
    compileSdk = 37
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            matchingFallbacks += listOf("benchmark")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    useConnectedDevices = true
}

// AGP 9.4's TestedApks consumer otherwise lacks the Android environment
// already used by compile/runtime, selecting JVM/desktop KMP dependencies.
configurations.configureEach {
    if (name.endsWith("TestedApks")) {
        attributes {
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.ANDROID),
            )
        }
    }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
}

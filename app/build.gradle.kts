plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val geminiDevToken: String = providers.gradleProperty("geminiDevToken").getOrElse("")

android {
    namespace = "com.awaz.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awaz.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // standard = sideload / NGO builds. a11yTool = only for a build whose primary purpose is supporting users with disabilities, and only if that is genuinely true.
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
        }
        create("a11yTool") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            buildConfigField("String", "GEMINI_DEV_TOKEN", "\"\"")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            buildConfigField("String", "GEMINI_DEV_TOKEN", "\"$geminiDevToken\"")
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Kotlinx Coroutines & Serialization
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Networking (OkHttp for WebSocket)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.register("checkNoHardcodedApiKeys") {
    description = "Fails if any file under src/ contains a string matching pattern AIza[0-9A-Za-z_-]{35}"
    group = "verification"
    doLast {
        val pattern = Regex("AIza[0-9A-Za-z_-]{35}")
        val srcDir = file("src")
        var violations = 0
        srcDir.walkTopDown().filter { it.isFile && it.name != "NoHardcodedApiKeysTest.kt" }.forEach { file ->
            val text = file.readText()
            if (pattern.containsMatchIn(text)) {
                println("ERROR: File contains hardcoded API key: \${file.path}")
                violations++
            }
        }
        if (violations > 0) {
            throw GradleException("Found \$violations files containing hardcoded API keys matching pattern AIza...")
        }
    }
}


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aksa.mysignal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aksa.mysignal"
        minSdk = 30
        targetSdk = 35
        versionCode = 114
        versionName = "2.5.88"

        val collectorHost = (project.findProperty("mysignal.collectorHost") as String?)
            ?.trim()
            ?.ifEmpty { null }
            ?: "127.0.0.1"
        val collectorPort = (project.findProperty("mysignal.collectorPort") as String?)
            ?.trim()
            ?.toIntOrNull()
            ?: 8000
        val hmacKeyId = (project.findProperty("mysignal.hmacKeyId") as String?)
            ?.trim()
            ?.ifEmpty { null }
            ?: "unset"
        val hmacKeyHex = (project.findProperty("mysignal.hmacKeyHex") as String?)
            ?.trim()
            ?.ifEmpty { null }
            ?: "00"
        val measureKeyHex = (project.findProperty("mysignal.measureKeyHex") as String?)
            ?.trim()
            ?.ifEmpty { null }
            ?: "00"
        val reportingEnabled = hmacKeyId != "unset" && hmacKeyHex.length >= 32
        val measureEnabled = reportingEnabled && Regex("[0-9a-fA-F]{64}").matches(measureKeyHex)

        buildConfigField("String", "COLLECTOR_HOST", "\"${collectorHost.replace("\"", "")}\"")
        buildConfigField("int", "COLLECTOR_PORT", collectorPort.toString())
        buildConfigField("String", "HMAC_KEY_ID", "\"${hmacKeyId.replace("\"", "")}\"")
        buildConfigField("String", "HMAC_KEY_HEX", "\"${hmacKeyHex.replace("\"", "")}\"")
        buildConfigField("String", "MEASURE_KEY_HEX", "\"${measureKeyHex.replace("\"", "")}\"")
        buildConfigField("boolean", "REPORTING_ENABLED", reportingEnabled.toString())
        buildConfigField("boolean", "MEASURE_ENABLED", measureEnabled.toString())
        buildConfigField("int", "SPEEDTEST_PORT", "443")
        buildConfigField("String", "SPEEDTEST_PATH", "\"/speedtest/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        viewBinding = true
        buildConfig = true
    }

    // Extract libtraceroute.so so ProcessBuilder can exec it (PingTools pattern).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    applicationVariants.configureEach {
        val vName = versionName
        outputs.configureEach {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "mysignal-$vName.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

import com.android.build.api.variant.BuildConfigField

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun cfg(property: String, envName: String, fallback: String): String {
    val prop = (project.findProperty(property) as String?)?.trim()?.takeIf { it.isNotEmpty() }
    val env = System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }
    return prop ?: env ?: fallback
}

android {
    namespace = "com.aksa.magnon"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aksa.magnon"
        minSdk = 30
        targetSdk = 35
        versionCode = 129
        versionName = "2.6.12"

        val collectorHost = cfg("mysignal.collectorHost", "MAGNON_COLLECTOR_HOST", "127.0.0.1")
        val collectorPort = cfg("mysignal.collectorPort", "MAGNON_COLLECTOR_PORT", "8000").toIntOrNull() ?: 8000
        val hmacKeyId = cfg("mysignal.hmacKeyId", "MAGNON_HMAC_KEY_ID", "unset")
        val hmacKeyHex = cfg("mysignal.hmacKeyHex", "MAGNON_HMAC_KEY_HEX", "00")
        val measureKeyHex = cfg("mysignal.measureKeyHex", "MAGNON_MEASURE_KEY_HEX", "00")
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
        val googleClientId = cfg("magnon.googleClientId", "MAGNON_GOOGLE_CLIENT_ID", "")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${googleClientId.replace("\"", "")}\"")
        buildConfigField("String", "FLAVOR_LEVEL", "\"lite\"")
        buildConfigField("boolean", "IS_PREMIUM", "false")
    }

    flavorDimensions += "level"
    productFlavors {
        create("lite") {
            dimension = "level"
            applicationId = "com.aksa.magnon.lite"
            resValue("string", "app_name", "Magnon Lite")
            buildConfigField("String", "FLAVOR_LEVEL", "\"lite\"")
            buildConfigField("boolean", "IS_PREMIUM", "false")
        }
        create("premium") {
            dimension = "level"
            applicationId = "com.aksa.magnon.premium"
            resValue("string", "app_name", "Magnon Premium")
            buildConfigField("String", "FLAVOR_LEVEL", "\"premium\"")
            buildConfigField("boolean", "IS_PREMIUM", "true")
        }
    }

    val keystorePath = System.getenv("KEYSTORE_PATH")?.trim().orEmpty()
    signingConfigs {
        create("release") {
            if (keystorePath.isNotEmpty()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("KEY_PASSWORD").orEmpty()
            }
        }
    }

    buildTypes {
        release {
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    androidComponents {
        onVariants { variant ->
            val gate = variant.name == "liteRelease" || variant.name == "premiumRelease"
            variant.buildConfigFields.put(
                "INTEGRITY_GATE",
                BuildConfigField(
                    "boolean",
                    if (gate) "true" else "false",
                    "Release builds exit when the device is rooted or developer options are on.",
                ),
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
        val flavor = flavorName
        outputs.configureEach {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "magnon-$flavor-$vName.apk"
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
    add("premiumImplementation", "org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

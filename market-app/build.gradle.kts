import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.malik05255.market"
    compileSdk = 36

    val explicitVersionCode = providers.gradleProperty("APP_VERSION_CODE")
        .orElse(providers.environmentVariable("APP_VERSION_CODE"))
        .orNull
        ?.toIntOrNull()
    val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
        .orNull
        ?.toIntOrNull()
    val resolvedVersionCode = explicitVersionCode ?: ciRunNumber?.let { 20_000 + it } ?: 20_000
    val resolvedVersionName = providers.gradleProperty("APP_VERSION_NAME")
        .orElse(providers.environmentVariable("APP_VERSION_NAME"))
        .orElse("2.0.0")
        .get()

    val signingStorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    val signingStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
    val signingKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
    val signingKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
    val stableSigningConfigured = listOf(
        signingStorePath,
        signingStorePassword,
        signingKeyAlias,
        signingKeyPassword
    ).all { !it.isNullOrBlank() }

    defaultConfig {
        applicationId = "com.malik05255.supermarket"
        minSdk = 29
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        fun quotedConfig(name: String, fallback: String = ""): String {
            val raw = providers.gradleProperty(name)
                .orElse(providers.environmentVariable(name))
                .orElse("")
                .get()
                .ifBlank { fallback }
            return "\"${raw.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }

        buildConfigField(
            "String",
            "SUPABASE_URL",
            quotedConfig("SUPABASE_URL", "https://lbgcjmsqqhrpceijdqng.supabase.co")
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            quotedConfig("SUPABASE_ANON_KEY", "sb_publishable_TllPSeKhRJx_IegHMxkZmA_Q9FLBUR_")
        )
        buildConfigField("String", "CLOUDFLARE_PRODUCTS_URL", quotedConfig("CLOUDFLARE_PRODUCTS_URL"))
        buildConfigField("String", "FIREBASE_DATABASE_URL", quotedConfig("FIREBASE_DATABASE_URL"))
    }

    signingConfigs {
        if (stableSigningConfigured) {
            create("stableInstaller") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (stableSigningConfigured) signingConfig = signingConfigs.getByName("stableInstaller")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (stableSigningConfigured) signingConfig = signingConfigs.getByName("stableInstaller")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose.android)
    implementation(libs.androidx.compose.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)

    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.content.negotiation)
    implementation(libs.ktor.serialization)

    testImplementation(libs.junit)
}

// Accuracy-first Arabic model. It is downloaded only at build time from an immutable
// Tesseract commit, verified by SHA-256, and then packaged into the APK. No runtime
// download and no product image leaves the device.
val prepareArabicTessdata by tasks.registering {
    val output = layout.projectDirectory.file("src/main/assets/tessdata/ara.traineddata").asFile
    val expectedSha256 = "ab9d157d8e38ca00e7e39c7d5363a5239e053f5b0dbdb3167dde9d8124335896"
    outputs.file(output)
    doLast {
        fun sha256(file: java.io.File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }

        if (output.exists() && output.length() > 5_000_000L && sha256(output) == expectedSha256) {
            println("Arabic tessdata_best already verified: ${output.length()} bytes")
            return@doLast
        }

        output.parentFile.mkdirs()
        if (output.exists()) output.delete()
        val source = URI(
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/e12c65a915945e4c28e237a9b52bc4a8f39a0cec/ara.traineddata"
        ).toURL()
        val connection = source.openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("User-Agent", "MoqarinAlasaarBuild/2.1")
        }
        connection.getInputStream().use { input ->
            output.outputStream().use { destination -> input.copyTo(destination) }
        }
        check(output.length() > 5_000_000L) { "Arabic tessdata_best download is unexpectedly small" }
        val actual = sha256(output)
        check(actual == expectedSha256) {
            "Arabic tessdata_best checksum mismatch: expected=$expectedSha256 actual=$actual"
        }
        println("Arabic tessdata_best verified: ${output.length()} bytes sha256=$actual")
    }
}

tasks.named("preBuild").configure { dependsOn(prepareArabicTessdata) }

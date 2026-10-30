import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.snapplication.launcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.snapplication.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 99
        versionName = "0.99"
        testInstrumentationRunner = "app.snapplication.launcher.RemoteLearningLifecycleTest"
    }
    // A local, ignored key keeps development updates compatible across Android Studio and test
    // tooling.
    signingConfigs.getByName("debug") {
        val localDebugKey = rootProject.file(".local/debug.keystore")
        if (localDebugKey.exists()) storeFile = localDebugKey
    }
    // Public builds are signed with a release key kept outside the repository in
    // .local/release-signing.properties (storeFile, storePassword, keyAlias, keyPassword).
    val releaseSigning = rootProject.file(".local/release-signing.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    if (releaseSigning != null) signingConfigs.create("release") {
        storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
        storePassword = releaseSigning.getProperty("storePassword")
        keyAlias = releaseSigning.getProperty("keyAlias")
        keyPassword = releaseSigning.getProperty("keyPassword")
    }
    buildTypes {
        getByName("release") {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "edition"
    productFlavors {
        create("universal") {
            // The shared manifest opens as Universal by default; Reader and TV remain separate
            // editions.
            isDefault = true
            dimension = "edition"
            resValue("string", "app_name", "Snapplication")
            resValue("string", "remote_support_name", "Snapplication remote and preview support")
        }
        create("modern") {
            dimension = "edition"
            applicationIdSuffix = ".modern"
            minSdk = 30
            resValue("string", "app_name", "Snapplication Modern")
            resValue("string", "remote_support_name", "Snapplication Modern remote and page preview support")
        }
        create("ereader") {
            dimension = "edition"
            applicationIdSuffix = ".ereader"
            resValue("string", "app_name", "Snapplication E-reader")
            resValue("string", "remote_support_name", "Snapplication E-reader support")
        }
        create("tv") {
            dimension = "edition"
            applicationIdSuffix = ".tv"
            resValue("string", "app_name", "Snapplication TV")
            resValue("string", "remote_support_name", "Snapplication TV remote support")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    testImplementation("junit:junit:4.13.2")
}

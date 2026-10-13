plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedWebAssets = layout.buildDirectory.dir("generated/wayToConsistencyAssets")

val bundleWebApp by tasks.registering(Copy::class) {
    from(rootProject.file("../way-to-consistency.html")) {
        rename { "index.html" }
    }
    from(rootProject.file("../assets")) {
        into("assets")
    }
    into(generatedWebAssets)
}

android {
    namespace = "com.waytoconsistency"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.waytoconsistency"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    sourceSets.getByName("main").assets.srcDir(generatedWebAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild").configure {
    dependsOn(bundleWebApp)
}
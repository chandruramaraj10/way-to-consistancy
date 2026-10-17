plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI or restricted workspaces can redirect generated output without changing source assets.
providers.gradleProperty("wtcBuildDir").orNull?.let { layout.buildDirectory.set(file(it)) }

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

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha12")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

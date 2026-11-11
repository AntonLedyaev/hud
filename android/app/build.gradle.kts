plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.diyhud"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "dev.diyhud"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.google.ai.edge.litert:litert:1.4.0")
}

// Experimental weights exist only in the test APK, never in the application.
val experimentAssets = layout.buildDirectory.dir("generated/experimentAssets")
val stageExperiment by tasks.registering(Copy::class) {
    val experimentRoot = rootProject.layout.projectDirectory.dir("../ml/runs/export_experiment")
    from(experimentRoot) {
        include("input_nhwc.bin", "expected_output.bin", "report.json")
    }
    from(experimentRoot.dir("saved_model")) {
        include("*_float32.tflite")
        rename { "experiment.tflite" }
    }
    into(experimentAssets)
    doFirst {
        for (name in listOf("input_nhwc.bin", "expected_output.bin", "report.json")) {
            check(experimentRoot.file(name).asFile.isFile) { "Missing source fixture $name" }
        }
        check(experimentRoot.dir("saved_model").asFile.listFiles()
            ?.count { it.name.endsWith("_float32.tflite") } == 1) {
            "Expected exactly one source FP32 model. Run the export experiment first."
        }
    }
    doLast {
        for (name in listOf("input_nhwc.bin", "expected_output.bin", "report.json", "experiment.tflite")) {
            check(experimentAssets.get().file(name).asFile.isFile) {
                "Missing experiment fixture $name. Run ml/export/experiment.py first."
            }
        }
    }
}
android.sourceSets.getByName("androidTest").assets.srcDir(experimentAssets)
// Android source sets do not infer TaskProvider dependencies for generated assets.
// Include lint consumers as well as packaging, so clean builds are ordered too.
tasks.matching { "AndroidTest" in it.name }.configureEach {
    dependsOn(stageExperiment)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.diyhud"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        applicationId = "dev.diyhud"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    testOptions.unitTests.all {
        it.systemProperty("hud.repoRoot", rootProject.projectDir.parentFile.absolutePath)
    }
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    androidTestImplementation("com.google.ai.edge.litert:litert-gpu-api:1.4.0")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
android.sourceSets.getByName("androidTest").assets.srcDir("schemas")

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

val generateCanonicalLabels by tasks.registering(Exec::class) {
    val repository = rootProject.projectDir.parentFile
    val windows = System.getProperty("os.name").startsWith("Windows")
    val python = providers.gradleProperty("labelsPython").getOrElse(
        repository.resolve(if (windows) "ml/.venv/Scripts/python.exe" else "ml/.venv/bin/python").path
    )
    inputs.files(repository.resolve("shared/labels.yaml"), repository.resolve("shared/contracts.py"))
    outputs.file(layout.buildDirectory.file("generated/canonical/labels.json"))
    commandLine(python, repository.resolve("shared/contracts.py"), "--generate-labels",
        layout.buildDirectory.file("generated/canonical/labels.json").get().asFile)
}
tasks.matching { it.name.startsWith("test") && it.name.endsWith("UnitTest") }.configureEach {
    dependsOn(generateCanonicalLabels)
}

val trainedBundleAssets = layout.buildDirectory.dir("generated/trainedBundleAssets")
val stageTrainedBundle by tasks.registering(Exec::class) {
    val repository = rootProject.projectDir.parentFile
    val source = providers.gradleProperty("modelBundleDir")
        .getOrElse(repository.resolve("ml/runs/trained-bundle-v2").path)
    val python = providers.gradleProperty("labelsPython").getOrElse(
        repository.resolve(if (System.getProperty("os.name").startsWith("Windows"))
            "ml/.venv/Scripts/python.exe" else "ml/.venv/bin/python").path)
    // Always validate before copying: a locally changed artifact cannot hide behind a cached task.
    commandLine(python, repository.resolve("ml/export/stage_bundle.py"), "--source", source,
        "--destination", trainedBundleAssets.get().dir("model").asFile)
}
android.sourceSets.getByName("main").assets.srcDir(trainedBundleAssets)
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/canonical"))
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateCanonicalLabels)
}
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets") && "AndroidTest" !in it.name) ||
        (it.name.startsWith("generate") && "Lint" in it.name && it.name.endsWith("Model"))
}.configureEach {
    dependsOn(stageTrainedBundle)
}
val trainedFixtureAssets = layout.buildDirectory.dir("generated/trainedFixtureAssets")
val stageTrainedFixtures by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile.resolve("ml/runs/trained-bundle-v2/fixtures"))
    into(trainedFixtureAssets.map { it.dir("trained") })
}
android.sourceSets.getByName("androidTest").assets.srcDir(trainedFixtureAssets)
tasks.matching { "AndroidTest" in it.name }.configureEach { dependsOn(stageTrainedFixtures) }

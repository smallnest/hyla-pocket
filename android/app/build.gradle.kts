plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.rpcx.hylapocket"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.rpcx.hylapocket"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.3"

        // libghostty-vt is built for arm64 only, so the APK must not claim the other ABIs either.
        ndk.abiFilters += "arm64-v8a"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The native libraries stay uncompressed in the APK and are mapped from it, as Android 15's
    // 16 KB page size check requires.
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// libghostty-vt, the official Ghostty terminal library, from the revision pinned in
// native/ghostty/source.json, with the JNI layer beside it: see tools/build-ghostty.py.
val buildGhostty by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libghostty_jni.so from the pinned official Ghostty source."
    commandLine("python3", rootProject.file("tools/build-ghostty.py").absolutePath)
    inputs.dir(rootProject.file("native/ghostty"))
    inputs.file(rootProject.file("tools/build-ghostty.py"))
    outputs.file(layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libghostty_jni.so"))
}
tasks.named("preBuild") { dependsOn(buildGhostty) }

// libtailcat.so, the tailcat client the app reaches a host through, for each ABI of jniLibs: see
// tools/build-tailcat.py. It also makes the darwin library the unit tests load, which needs
// jni.h: JAVA_HOME, or the JDK Android Studio runs Gradle with.
val buildTailcat by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds libtailcat.so for every ABI of jniLibs, and for this computer for the tests."
    val script = rootProject.file("tools/build-tailcat.py")
    commandLine("python3", script.absolutePath, "--host")
    // Resolved here: a Provider handed to Exec's environment reaches the script as its toString.
    environment(
        "JAVA_HOME",
        providers.environmentVariable("JAVA_HOME").orElse(providers.systemProperty("java.home")).get(),
    )
    inputs.dir(rootProject.file("native/tailcat"))
    inputs.file(script)
    // Files, not directories: libghostty_jni.so, the other file of arm64-v8a, is not this task's.
    outputs.files(
        rootProject.file("app/src/main/jniLibs/arm64-v8a/libtailcat.so"),
        rootProject.file("app/src/main/jniLibs/x86_64/libtailcat.so"),
        rootProject.file("native/.build/tailcat/host/libtailcat.dylib"),
    )
}
tasks.named("preBuild") { dependsOn(buildTailcat) }
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR codes: CameraX shows the camera, ZXing reads its frames on our own thread, MlKit reads
    // still pictures and the frames ZXing could not.
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "com.aistudio.pizzora.xkqzmw"
    minSdk = 24
    targetSdk = 36
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
    val resolvedVersionCode = providers.gradleProperty("pizzoraVersionCode").orNull?.toIntOrNull()
      ?: providers.environmentVariable("ANDROID_VERSION_CODE").orNull?.toIntOrNull()
      ?: 1
    val resolvedVersionName = providers.gradleProperty("pizzoraVersionName").orNull?.takeIf { it.isNotBlank() }
      ?: providers.environmentVariable("ANDROID_VERSION_NAME").orNull?.takeIf { it.isNotBlank() }
      ?: "1.0"
    versionCode = resolvedVersionCode
    versionName = resolvedVersionName

    buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080\"")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      val releaseApiBaseUrl = (System.getenv("PIZZORA_API_BASE_URL") ?: "https://api.pizzora.app")
        .replace("\"", "")
      buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
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
  val rustWorkspaceDir = rootProject.layout.projectDirectory.dir("rust").asFile
  val rustHostTargetReleaseDir = rustWorkspaceDir.resolve("target/release")
  val uniffiGeneratedKotlinDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
  val uniffiAndroidJniLibsDir = layout.buildDirectory.dir("generated/uniffi/jniLibs")

  sourceSets {
    getByName("main") {
      kotlin.srcDir(uniffiGeneratedKotlinDir)
      jniLibs.srcDir(uniffiAndroidJniLibsDir)
    }
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      all {
        it.systemProperty("java.library.path", rustHostTargetReleaseDir.absolutePath)
        it.systemProperty("jna.library.path", rustHostTargetReleaseDir.absolutePath)
      }
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

val rustWorkspaceDir = rootProject.layout.projectDirectory.dir("rust").asFile
val rustHostTargetReleaseDir = rustWorkspaceDir.resolve("target/release")
val uniffiGeneratedKotlinDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val uniffiAndroidJniLibsDir = layout.buildDirectory.dir("generated/uniffi/jniLibs")
val hostOsLower = System.getProperty("os.name").lowercase()
val hostCdylibName = when {
  hostOsLower.contains("mac") -> "libpizzora_uniffi.dylib"
  hostOsLower.contains("win") -> "pizzora_uniffi.dll"
  else -> "libpizzora_uniffi.so"
}
val hostCdylibFile = rustHostTargetReleaseDir.resolve(hostCdylibName)

fun resolveRustPath(): String {
  val cargoBins = listOfNotNull(
    System.getenv("CARGO_HOME")?.let { File(it, "bin") },
    File(System.getProperty("user.home"), ".cargo/bin"),
  ).filter { it.isDirectory }.map { it.absolutePath }
  val existingPath = System.getenv("PATH").orEmpty()
  return (cargoBins + existingPath).filter { it.isNotBlank() }.joinToString(File.pathSeparator)
}

fun resolveAndroidNdkHome(sdkDir: File?): String {
  System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { return it }
  System.getenv("ANDROID_NDK_ROOT")?.takeIf { it.isNotBlank() }?.let { return it }
  val sdkRoot = sdkDir
    ?: System.getenv("ANDROID_SDK_ROOT")?.let(::File)
    ?: System.getenv("ANDROID_HOME")?.let(::File)
    ?: File("/opt/android/sdk")
  val pinned = File(sdkRoot, "ndk/27.2.12479018")
  if (pinned.isDirectory) return pinned.absolutePath
  error("Android NDK 27.2.12479018 not found under ${sdkRoot.absolutePath}.")
}

val buildHostRustLib by tasks.registering(Exec::class) {
  group = "rust"
  description = "Builds host cdylib for UniFFI Kotlin binding generation and Robolectric tests."
  workingDir = rustWorkspaceDir
  inputs.dir(rustWorkspaceDir.resolve("crates/pizzora-core"))
  inputs.dir(rustWorkspaceDir.resolve("crates/pizzora-uniffi"))
  inputs.files(
    rustWorkspaceDir.resolve("Cargo.toml"),
    rustWorkspaceDir.resolve("Cargo.lock"),
    rustWorkspaceDir.resolve("rust-toolchain.toml"),
  )
  outputs.file(hostCdylibFile)
  environment("PATH", resolveRustPath())
  environment("CARGO_BUILD_JOBS", "2")
  commandLine("cargo", "build", "-p", "pizzora-uniffi", "--release", "--locked")
}

val generateUniFfiKotlinBindings by tasks.registering(Exec::class) {
  group = "rust"
  description = "Generates Kotlin UniFFI bindings from the host pizzora_uniffi cdylib."
  dependsOn(buildHostRustLib)
  workingDir = rustWorkspaceDir
  inputs.file(hostCdylibFile)
  inputs.dir(rustWorkspaceDir.resolve("crates/pizzora-uniffi/src"))
  outputs.dir(uniffiGeneratedKotlinDir)
  environment("PATH", resolveRustPath())
  doFirst {
    val outDir = uniffiGeneratedKotlinDir.get().asFile
    outDir.deleteRecursively()
    outDir.mkdirs()
  }
  commandLine(
    "cargo",
    "run",
    "-p",
    "pizzora-uniffi",
    "--bin",
    "uniffi-bindgen",
    "--locked",
    "--",
    "generate",
    "--library",
    hostCdylibFile.absolutePath,
    "--language",
    "kotlin",
    "--out-dir",
    uniffiGeneratedKotlinDir.get().asFile.absolutePath,
  )
}

val buildAndroidRustLibs by tasks.registering(Exec::class) {
  group = "rust"
  description = "Cross-compiles Android arm64-v8a and x86_64 pizzora_uniffi shared libraries via cargo-ndk."
  workingDir = rustWorkspaceDir
  inputs.dir(rustWorkspaceDir.resolve("crates/pizzora-core"))
  inputs.dir(rustWorkspaceDir.resolve("crates/pizzora-uniffi"))
  inputs.files(
    rustWorkspaceDir.resolve("Cargo.toml"),
    rustWorkspaceDir.resolve("Cargo.lock"),
    rustWorkspaceDir.resolve("rust-toolchain.toml"),
  )
  outputs.dir(uniffiAndroidJniLibsDir)
  environment("PATH", resolveRustPath())
  environment("CARGO_BUILD_JOBS", "2")
  doFirst {
    val outDir = uniffiAndroidJniLibsDir.get().asFile
    outDir.deleteRecursively()
    outDir.mkdirs()
    environment("ANDROID_NDK_HOME", resolveAndroidNdkHome(android.sdkDirectory))
  }
  commandLine(
    "cargo",
    "ndk",
    "-t",
    "arm64-v8a",
    "-t",
    "x86_64",
    "-o",
    uniffiAndroidJniLibsDir.get().asFile.absolutePath,
    "build",
    "-p",
    "pizzora-uniffi",
    "--release",
    "--locked",
  )
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  dependsOn(generateUniFfiKotlinBindings)
}

tasks.configureEach {
  if (name.startsWith("ksp") && name.endsWith("Kotlin")) {
    dependsOn(generateUniFfiKotlinBindings)
  }
  if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
    dependsOn(buildAndroidRustLibs)
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

configurations.configureEach {
  if (name in setOf(
      "debugCompileClasspath",
      "debugRuntimeClasspath",
      "releaseCompileClasspath",
      "releaseRuntimeClasspath",
    )
  ) {
    resolutionStrategy.activateDependencyLocking()
  }
}

dependencies {
  implementation(project(":shared:contracts"))

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.coil.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.ktor.client.auth)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.websockets)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.okhttp)
  implementation(libs.razorpay.checkout)
  implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.jna)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.ktor.client.mock)
  testImplementation(libs.robolectric)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}

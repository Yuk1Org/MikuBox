import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.time.LocalDate
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.aboutLibraries)
}

// The about screen reads the licence list the plugin generates from the
// dependency graph, exactly as MikuRay's does.
aboutLibraries {
    collect {
        includeTestVariants.set(false)
        filterVariants.addAll("debug", "release")
    }
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String? =
    (findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "top.uwu.mikubox"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "29.0.13599879"

    defaultConfig {
        testInstrumentationRunner = "top.uwu.mikubox.RuntimeSmokeInstrumentation"
        applicationId = "top.uwu.mikubox"
        minSdk = 24
        targetSdk = 36
        // Release workflows override both with the pushed tag (and the CI run
        // number, which only ever grows) so a tagged build reports and sorts
        // as the version it publishes; local builds keep the checked-in values.
        versionCode = 10
        versionName = "UwU-1.0.0"

        // The ported banner card (uwu_banner_theme / uwu_maintainer) reads these
        // the same way MikuRay's does — MikuRay declares them as resValues in its
        // build script, so the vendored layouts expect the names to exist.
        val bannerVersionName = versionName ?: "UwU-1.0.0"
        resValue("string", "uwu_version_name", bannerVersionName)
        resValue("string", "uwu_package_name", "top.uwu.mikubox")
        resValue("string", "uwu_build_date", LocalDate.now().toString())

        externalNativeBuild {
            cmake {
                arguments += "-DMIHOMO_JNI_LIBS_DIR=${layout.buildDirectory.get().asFile}/generated/mihomo-jniLibs"
            }
        }
    }

    val keystorePass = secret("KEYSTORE_PASS")
    if (keystorePass != null) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(secret("KEYSTORE_PATH") ?: "release.keystore")
                storePassword = keystorePass
                keyAlias = secret("ALIAS_NAME")
                keyPassword = secret("ALIAS_PASS")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildFeatures {
        viewBinding = true
        // The ported screens read BuildConfig.VERSION_NAME / APPLICATION_ID the
        // way MikuRay's do (the about screen prints both).
        buildConfig = true
    }

    // MikuRay's vendored sources are compiled by :mikuray-ui (they need to see
    // the view bindings generated there), so this module only compiles MikuBox's
    // own code and consumes the rest as a dependency. The java filter alone
    // leaves the .kt files to this module's Kotlin compiler, which then ships
    // duplicate classes the release dexer rejects.
    sourceSets.getByName("main").java.filter.exclude("com/miku/ray/**")

    testOptions.unitTests.isIncludeAndroidResources = true

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // ART can map DEX directly instead of inflating every file on cold
        // starts. Keep this explicit for Debug APKs as well as Release.
        dex {
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf(
                "DebugProbesKt.bin",
                "META-INF/**"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // MikuRay's sources use java.time and java.util.stream on API 24.
        isCoreLibraryDesugaringEnabled = true
    }
}

val mihomoBridgeDir = rootProject.file("core/mihomo-bridge")
val mihomoSourceDir = rootProject.file("core/mihomo")
val mihomoJniLibsDir = layout.buildDirectory.dir("generated/mihomo-jniLibs")

android.sourceSets.getByName("main").jniLibs.srcDir(mihomoJniLibsDir)

val buildMihomoBridge by tasks.registering {
    group = "build"
    description = "Builds the bundled HSSkyBoy/mihomo Alpha JNI bridge for every Android ABI."
    inputs.dir(mihomoBridgeDir)
    inputs.dir(rootProject.file("core/patches"))
    inputs.dir(mihomoSourceDir)
    outputs.dir(mihomoJniLibsDir)

    doLast {
        val overlay = layout.buildDirectory.file("mihomo-overlay.json").get().asFile
        overlay.parentFile.mkdirs()
        fun jsonPath(file: File) = file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        overlay.writeText("""{"Replace":{"${jsonPath(mihomoSourceDir.resolve("listener/sing_tun/server_notwindows.go"))}":"${jsonPath(rootProject.file("core/patches/server_notwindows.go"))}","${jsonPath(mihomoSourceDir.resolve("dns/patch_android.go"))}":"${jsonPath(rootProject.file("core/patches/patch_android.go"))}"}}""")
        val ndkDir = android.ndkDirectory
        val hostOs = System.getProperty("os.name").lowercase()
        val (hostTag, exeExt) = when {
            hostOs.contains("win") -> "windows-x86_64" to ".cmd"
            hostOs.contains("mac") || hostOs.contains("darwin") -> "darwin-x86_64" to ""
            else -> "linux-x86_64" to ""
        }
        val clangDir = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
        val targets = mapOf(
            "armeabi-v7a" to "armv7a-linux-androideabi24-clang",
            "arm64-v8a" to "aarch64-linux-android24-clang",
            "x86_64" to "x86_64-linux-android24-clang"
        )

        targets.forEach { (abi, compiler) ->
            val output = mihomoJniLibsDir.get().file("$abi/libmihomo.so").asFile
            output.parentFile.mkdirs()

            val goArch = when (abi) {
                "armeabi-v7a" -> "arm"
                "arm64-v8a" -> "arm64"
                "x86_64" -> "amd64"
                else -> error("Unsupported Android ABI: $abi")
            }

            exec {
                workingDir = mihomoBridgeDir
                environment("GOOS", "android")
                environment("GOARCH", goArch)
                if (abi == "armeabi-v7a") environment("GOARM", "7")
                environment("CGO_ENABLED", "1")
                environment("CC", clangDir.resolve(compiler + exeExt).absolutePath)

                commandLine(
                    // with_gvisor matches Mihomo's own release build; without the
                    // tag the gvisor/mixed TUN stacks are unavailable at runtime.
                    //
                    // cmfa drops Mihomo's root-only Android paths. Its sing-tun
                    // binding reads /data/system/packages.xml to build per-app
                    // rules; an unprivileged app gets EACCES, that step fails, and
                    // the TUN listener is never started while hub.Parse still
                    // reports success - a VPN that is up with no traffic at all.
                    // The tag is Mihomo's own switch for apps that embed the core
                    // and own the VpnService themselves.
                    "go", "build", "-overlay=${overlay.absolutePath}", "-trimpath", "-buildmode=c-shared",
                    "-tags", "with_gvisor cmfa",
                    "-ldflags=-s -w", "-o", output.absolutePath, "."
                )
            }
        }
    }
}

tasks.configureEach {
    if (name.contains("CMake") || name.endsWith("JniLibFolders")) {
        dependsOn(buildMihomoBridge)
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // Keep this module from recompiling the vendored sources that belong to
    // :mikuray-ui (see the source set exclude above for why).
    exclude("com/miku/ray/**")
}

val stripDebugApkMetadata by tasks.registering {
    dependsOn("packageDebug")

    doLast {
        val outputDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
        val releaseKeystore = rootProject.file(secret("KEYSTORE_PATH") ?: "release.keystore")
        val releaseStorePass = secret("KEYSTORE_PASS")
        val releaseAlias = secret("ALIAS_NAME")
        val releaseKeyPass = secret("ALIAS_PASS")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val exeExt = if (isWindows) ".exe" else ""
        val keytool = File(System.getProperty("java.home"), "bin/keytool$exeExt")

        val useReleaseKey = releaseKeystore.isFile &&
            releaseStorePass != null && releaseAlias != null && releaseKeyPass != null
        if (!useReleaseKey && !debugKeystore.isFile) {
            debugKeystore.parentFile.mkdirs()
            check(keytool.isFile) { "JDK keytool was not found: $keytool" }
            exec {
                commandLine(
                    keytool.absolutePath,
                    "-genkeypair",
                    "-keystore", debugKeystore.absolutePath,
                    "-storepass", "android",
                    "-keypass", "android",
                    "-alias", "androiddebugkey",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "10000",
                    "-dname", "CN=Android Debug,O=Android,C=US",
                    "-noprompt"
                )
            }
        }
        val signingKeystore = if (useReleaseKey) releaseKeystore else debugKeystore
        val signingStorePass = if (useReleaseKey) releaseStorePass!! else "android"
        val signingAlias = if (useReleaseKey) releaseAlias!! else "androiddebugkey"
        val signingKeyPass = if (useReleaseKey) releaseKeyPass!! else "android"

        val buildToolsDir = android.sdkDirectory.resolve("build-tools/${android.buildToolsVersion}")
        val zipalign = buildToolsDir.resolve("zipalign$exeExt")
        val apksigner = buildToolsDir.resolve("apksigner${if (isWindows) ".bat" else ""}")
        check(zipalign.isFile && apksigner.isFile) { "Android build-tools are incomplete in $buildToolsDir" }

        outputDir.listFiles { file -> file.extension == "apk" }?.forEach { apk ->
            val unaligned = apk.resolveSibling("${apk.nameWithoutExtension}-stripped-unaligned.apk")
            val aligned = apk.resolveSibling("${apk.nameWithoutExtension}-stripped-aligned.apk")
            ZipFile(apk).use { input ->
                ZipOutputStream(unaligned.outputStream().buffered()).use { output ->
                    val entries = input.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name == "DebugProbesKt.bin" || entry.name.startsWith("META-INF/")) continue

                        val newEntry = ZipEntry(entry.name).apply {
                            time = entry.time
                            method = entry.method
                            if (entry.method == ZipEntry.STORED) {
                                size = entry.size
                                compressedSize = entry.compressedSize
                                crc = entry.crc
                            }
                        }

                        output.putNextEntry(newEntry)
                        if (!entry.isDirectory) {
                            input.getInputStream(entry).use { it.copyTo(output) }
                        }
                        output.closeEntry()
                    }
                }
            }

            exec { commandLine(zipalign.absolutePath, "-f", "4", unaligned.absolutePath, aligned.absolutePath) }
            exec {
                commandLine(
                    apksigner.absolutePath, "sign",
                    "--ks", signingKeystore.absolutePath,
                    "--ks-key-alias", signingAlias,
                    "--ks-pass", "pass:$signingStorePass",
                    "--key-pass", "pass:$signingKeyPass",
                    "--v1-signing-enabled", "false",
                    "--out", apk.absolutePath,
                    aligned.absolutePath
                )
            }

            unaligned.delete()
            aligned.delete()
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(stripDebugApkMetadata)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.zxing.core)

    // MikuRay's design layer (resources) and its icon set. Both are
    // resource-only modules; the screens that consume them live in this module
    // under com.miku.ray.*.
    implementation(project(":mikuray-ui"))
    implementation(project(":remixicon"))

    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)

    // Open-source licence list on the about screen.
    implementation(libs.aboutlibraries.view)

    // MikuRay's vendored sources use these directly.
    implementation(libs.glide)
    implementation(libs.ucrop)
    implementation(libs.editorkit)
    implementation(libs.language.base)
    implementation(libs.language.json)
    implementation(libs.flexbox)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.snakeyaml)
    implementation(libs.mmkv)
    implementation(libs.play.services.location)
    implementation(libs.zxing.lite)
    implementation(libs.work.multiprocess)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

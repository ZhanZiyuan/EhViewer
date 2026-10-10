import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import java.util.Properties
import java.util.regex.Pattern

plugins {
    alias(libs.plugins.ehviewer.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibrariesAndroid)
    alias(libs.plugins.baselineprofile)
}

val releaseVersion = "1.15.5"

val supportedAbis = arrayOf("arm64-v8a", "x86_64", "armeabi-v7a")
val releaseAbi = providers.gradleProperty("releaseAbi").orNull
require(releaseAbi == null || releaseAbi in supportedAbis || releaseAbi == "universal") {
    "releaseAbi must be arm64-v8a, armeabi-v7a, x86_64 or universal"
}
val buildAbis = releaseAbi?.takeUnless { it == "universal" }?.let { arrayOf(it) } ?: supportedAbis

// The immutable regression baseline remains in Git history, not in the current tree.
val prepareNativeTestFixtures = tasks.register<NativeTestFixturesTask>("prepareNativeTestFixtures") {
    baseline.set("8fdfb6c5550fab506759a5587b1c8ee4050f507e")
    outputDirectory.set(rootProject.layout.projectDirectory.dir(".local-test-fixtures"))
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(prepareNativeTestFixtures) { it.outputDirectory }
    }
}

android {
    splits {
        abi {
            isEnable = releaseAbi != "universal"
            reset()
            include(*buildAbis)
            isUniversalApk = releaseAbi == null
        }
    }

    val signingProperties = Properties().apply {
        rootProject.file(".private-signing/signing.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    }
    fun signingValue(environment: String, property: String): String? = providers.environmentVariable(environment).orNull ?: signingProperties.getProperty(property)
    val signingFile = file(providers.environmentVariable("EHVIEWER_KEYSTORE").orElse(rootProject.file(".private-signing/androidkey.jks").absolutePath))
    val signConfig = if (signingFile.isFile) {
        signingConfigs.create("release") {
            storeFile = signingFile
            storePassword = signingValue("EHVIEWER_STORE_PASSWORD", "storePassword")
            keyAlias = signingValue("EHVIEWER_KEY_ALIAS", "keyAlias")
            keyPassword = signingValue("EHVIEWER_KEY_PASSWORD", "keyPassword")
            require(listOf(storePassword, keyAlias, keyPassword).all { !it.isNullOrEmpty() }) { "Release signing credentials are incomplete" }
            enableV3Signing = true
            enableV4Signing = true
        }
    } else {
        require(!hasProperty("release")) { "A production release requires the original external signing key" }
        signingConfigs.getByName("debug") // Secret-free CI snapshots cannot be installed over production.
    }

    val commitSha = providers.exec {
        commandLine = "git rev-parse --short=7 HEAD".split(' ')
    }.standardOutput.asText.get().trim()

    val commitTime = providers.exec {
        commandLine = "git log -1 --format=%ct".split(' ')
    }.standardOutput.asText.get().trim()

    val repoName = providers.exec {
        commandLine = "git remote get-url origin".split(' ')
    }.standardOutput.asText.get().trim().removePrefix("https://github.com/").removePrefix("git@github.com:")
        .removeSuffix(".git")

    val snapshot = !hasProperty("release")

    defaultConfig {
        applicationId = "moe.tarsin.ehviewer"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 180069
        versionName = if (snapshot) {
            "$releaseVersion-SNAPSHOT"
        } else {
            releaseVersion
        }
        buildConfigField("boolean", "SNAPSHOT", "$snapshot")
        buildConfigField("String", "RAW_VERSION_NAME", "\"$versionName\"")
        buildConfigField("String", "COMMIT_SHA", "\"$commitSha\"")
        buildConfigField("long", "COMMIT_TIME", commitTime)
        buildConfigField("String", "REPO_NAME", "\"$repoName\"")
        ndk {
            abiFilters.addAll(buildAbis)
            debugSymbolLevel = "FULL"
        }
    }

    externalNativeBuild {
        cmake {
            path = rootProject.file("native/third-party/CMakeLists.txt")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        dex {
            useLegacyPackaging = false
        }
        jniLibs {
            excludes += "**/libdatastore_shared_counter.so" // DataStore multi-process
        }
        resources {
            // Required by Layout Inspector
            pickFirsts += "/META-INF/androidx.compose.ui_ui.version"

            excludes += listOf(
                "/META-INF/**",
                "/kotlin/**",
                "**.txt",
                "**.bin",
            )
        }
    }

    androidResources {
        ignoreAssetsPatterns += listOf(
            "!PublicSuffixDatabase.list", // OkHttp
            "!composepreference.preference.generated.resources",
        )
        generateLocaleConfig = true
        localeFilters += listOf(
            "zh",
            "zh-rCN",
            "zh-rHK",
            "zh-rTW",
            "es",
            "ja",
            "ko",
            "fr",
            "de",
            "th",
            "tr",
            "nb-rNO",
        )
    }

    dependenciesInfo.includeInApk = false

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = signConfig
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        create("benchmarkRelease") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    namespace = "com.hippo.ehviewer"
}

baselineProfile {
    mergeIntoMain = true
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)

    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.i18n)
    implementation(projects.core.ui)

    // https://developer.android.com/jetpack/androidx/releases/activity
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)

    implementation(libs.compose.destinations.core)
    ksp(libs.compose.destinations.compiler)

    implementation(libs.compose.preference) {
        // R8 won't remove it because it adds a content provider
        exclude(group = "org.jetbrains.compose.components", module = "components-resources")
    }

    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)

    implementation(libs.androidx.datastore)

    // https://developer.android.com/jetpack/androidx/releases/lifecycle
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.compose)

    // https://developer.android.com/jetpack/androidx/releases/paging
    implementation(libs.androidx.paging.compose)

    // https://developer.android.com/jetpack/androidx/releases/room
    implementation(libs.androidx.room.paging)

    implementation(libs.androidx.work.runtime)
    implementation(libs.material.motion.core)
    implementation(libs.material.kolor)

    implementation(libs.bundles.splitties)

    // https://square.github.io/okhttp/changelogs/changelog/
    implementation(platform(libs.okhttp.bom))

    implementation(libs.logcat)

    implementation(libs.diff)

    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.accompanist.drawable.painter)

    implementation(libs.reorderable)

    implementation(platform(libs.arrow.stack))
    implementation(libs.bundles.arrow)

    // https://coil-kt.github.io/coil/changelog/
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)

    implementation(libs.telephoto.zoomable)

    implementation(libs.ktor.client.okhttp)

    implementation(libs.bundles.kotlinx.serialization)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":benchmark"))

    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "coil3.annotation.ExperimentalCoilApi",
            "androidx.paging.ExperimentalPagingApi",
            "me.saket.telephoto.ExperimentalTelephotoApi",
        )
    }
}

ksp {
    arg("compose-destinations.codeGenPackageName", "com.hippo.ehviewer.ui")
}

aboutLibraries {
    collect {
        includePlatform = false
    }
    library {
        exclusionPatterns.add(Pattern.compile("org\\.jetbrains\\.(?:compose|androidx)\\..*"))
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.GROUP
    }
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Load local.properties: holds the SDK path and the Podcast Index API credentials.
// This file is git-ignored and never enters version control, so secrets stay local.
// See local.properties.example in the repo root for the expected keys.
val localProperties =
    Properties().apply {
        val propsFile = rootProject.file("local.properties")
        if (propsFile.exists()) {
            propsFile.inputStream().use { load(it) }
        }
    }

// Warn at build time when Podcast Index credentials are missing, so contributors
// notice it during Gradle sync instead of hitting a runtime error later.
// This must stay a warning (not an error): the credentials are optional — without
// them the Podcast Index search source is unavailable, but everything else
// (Apple Podcasts search, OPML import) keeps working.
if (localProperties.getProperty("podcastIndex.apiKey").isNullOrBlank() ||
    localProperties.getProperty("podcastIndex.apiSecret").isNullOrBlank()
) {
    logger.warn(
        "Podcast Index credentials not found in local.properties " +
            "(podcastIndex.apiKey / podcastIndex.apiSecret). " +
            "The Podcast Index search source will be unavailable at runtime. " +
            "See local.properties.example in the repo root for setup instructions."
    )
}

// Version numbers are maintained here: versionName also feeds the APK file name
// (see the archivesName setting at the bottom of this file), so every delivered
// APK is self-identifying instead of a pile of identical app-debug.apk files.
val appVersionName = "1.1.16"

android {
    namespace = "com.ruwen.audioplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ruwen.audioplayer"
        minSdk = 29
        targetSdk = 34
        versionCode = 18
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Podcast Index API credentials: injected from local.properties via BuildConfig,
        // never hard-coded into the source. Sign up at https://api.podcastindex.org/signup
        buildConfigField(
            "String",
            "PODCAST_INDEX_API_KEY",
            "\"${localProperties.getProperty("podcastIndex.apiKey", "")}\""
        )
        buildConfigField(
            "String",
            "PODCAST_INDEX_API_SECRET",
            "\"${localProperties.getProperty("podcastIndex.apiSecret", "")}\""
        )

        vectorDrawables {
            useSupportLibrary = true
        }

        // NDK configuration
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cppFlags += "-fvisibility=hidden"
                arguments += listOf(
                    // Statically link the STL: the NDK's prebuilt libc++_shared.so is no
                    // longer packed into the APK. That prebuilt library only got 16 KB
                    // alignment in recent NDK releases and was the sole source of the
                    // "APK is not compatible with 16 KB devices" warning.
                    // This project ships a single shared library (libruwen_whisper.so),
                    // so static linking has no symbol-duplication risk.
                    "-DANDROID_STL=c++_static",
                    // Android 15+ 16 KB page sizes: make NDK/CMake emit 16 KB-aligned ELF files
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }

        ndk {
            // Build arm64 only:
            // 1) whisper.cpp officially provides full optimizations for arm64 only
            //    (WHISPER_ARM / quantized instructions);
            // 2) medium-tier inference needs GBs of RAM, which a 32-bit address
            //    space cannot hold;
            // 3) Android 10+ (minSdk 29) devices in the field are almost all arm64,
            //    keeping armeabi-v7a only triggers "no .so files available for armeabi-v7a".
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Note: since Kotlin 2.x, kotlinOptions { jvmTarget } is an error
    // ("Using 'jvmTarget: String' is an error"); migrated to the top-level
    // kotlin { compilerOptions { ... } } block below.
    // Whisper models (.bin) are large and MUST be declared uncompressed, otherwise
    // assets.openFd throws "This file can be compressed" and the model can never be
    // copied into app-private storage.
    // Note: since AGP 8.x, aaptOptions is deprecated; migrated to androidResources.
    androidResources {
        noCompress += "bin"
    }
    buildFeatures {
        compose = false
        viewBinding = true
        // Podcast feature reads the API credentials from local.properties via BuildConfig
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Keep in sync with android:extractNativeLibs="true" in the AndroidManifest;
            // silences AGP's "useLegacyPackaging should be set to true" warning.
            // Chose "extract at install time" over "store uncompressed in the APK for mmap"
            // to avoid depending on AGP's zip 16 KB alignment — stable on both
            // 4 KB and 16 KB page-size devices.
            useLegacyPackaging = true
        }
    }

    // CMake external native build
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Kotlin 2.x JVM target configuration (replaces the removed kotlinOptions block):
// kept consistent with android.compileOptions' Java 17.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room-exported schema directory (paired with exportSchema = true):
// the generated JSON files are the source of truth for the DB schema —
// write migrations against them column by column, and verify results with
// MigrationTestHelper in tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Version-suffixed APK name: RuWen-1.1.14-debug.apk / RuWen-1.1.14-release.apk
base {
    archivesName.set("RuWen-$appVersionName")
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Room
    // Room 2.6.1's KSP processor is incompatible with KSP 2.3.x
    // ("unexpected jvm signature V"), so Room was upgraded to the latest
    // stable 2.8.5 together with Kotlin/KSP.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Media
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")

    // DocumentFile (for storage access)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // CardView
    implementation("androidx.cardview:cardview:1.0.0")

    // ------------------------------------------------------------------
    //  Podcast feature
    // ------------------------------------------------------------------

    // RSS parsing: RSS / Atom / RDF with itunes:* podcast field support.
    // The official README states Android projects use com.prof18.rssparser:rssparser.
    implementation("com.prof18.rssparser:rssparser:6.1.2")

    // Networking: podcast search (Apple Podcasts / Podcast Index) and episode downloads
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // Image loading: podcast covers (3xN grid) and episode covers
    implementation("io.coil-kt.coil3:coil:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

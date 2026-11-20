plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

val chiakiRoot = layout.projectDirectory.dir("../third_party/chiaki-ng")
val chiakiAndroidApp = chiakiRoot.dir("android/app")

android {
    namespace = "com.metallic.chiaki" // Keep upstream R/package references stable.
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.pslink.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCHIAKI_ENABLE_TESTS=OFF",
                    "-DCHIAKI_ENABLE_CLI=OFF",
                    "-DCHIAKI_ENABLE_GUI=OFF",
                    "-DCHIAKI_ENABLE_SETSU=OFF",
                    "-DCHIAKI_ENABLE_ANDROID=ON",
                    "-DCHIAKI_ENABLE_FFMPEG_DECODER=OFF",
                    "-DCHIAKI_ENABLE_PI_DECODER=OFF",
                    "-DCHIAKI_LIB_ENABLE_OPUS=OFF",
                    "-DCHIAKI_LIB_OPENSSL_EXTERNAL_PROJECT=ON",
                    "-DCHIAKI_LIB_JSONC_EXTERNAL_PROJECT=ON",
                    "-DCHIAKI_LIB_MINIUPNPC_EXTERNAL_PROJECT=ON",
                    "-DCHIAKI_ENABLE_STEAM_SHORTCUT=OFF",
                    "-DCHIAKI_ENABLE_STEAMDECK_NATIVE=OFF",
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        prefab = true
    }

    sourceSets.getByName("main") {
        java.srcDir(chiakiAndroidApp.dir("src/main/java"))
        res.srcDir(chiakiAndroidApp.dir("src/main/res"))
        manifest.srcFile("src/main/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-opt-in=kotlin.ExperimentalUnsignedTypes"
    }

    externalNativeBuild {
        cmake {
            path = file("../third_party/chiaki-ng/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":core:shared"))
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.preference.ktx)
    implementation(libs.material)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)
    implementation(libs.oboe)

    // Bundled OCR models: they work without a first-run model download.
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)

    // On-device translation. Language packs are downloaded once, then used offline.
    implementation(libs.mlkit.translate)
    implementation(libs.okhttp)
    testImplementation("junit:junit:4.13.2")
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Aerial Lite: Home for Android 4.0-4.4 TVs, which the Compose app cannot run on (Compose needs
// Android 5.0). Plain Views, no AndroidX, so the APK stays small and Dalvik has little to load.
// Performance comes before looks here: see docs/LITE.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.orchard.tv.lite"
    compileSdk = 36

    defaultConfig {
        // Its own package: the 4.x TVs never get the full app, and the two update separately.
        applicationId = "app.orchard.tv.lite"
        // Android 4.0.
        minSdk = 14
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Signed with the same key as Aerial (see app/build.gradle.kts and docs/HANDOFF.md).
    val signingProps = rootProject.file("local/signing.properties")
    signingConfigs {
        if (signingProps.exists()) {
            val p = Properties().apply { signingProps.inputStream().use { load(it) } }
            create("aerial") {
                storeFile = rootProject.file("local/" + p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("aerial") ?: signingConfigs.getByName("debug")
        }
    }

    // The Apple aerial catalog is shared with the full app.
    sourceSets["main"].assets.srcDir("lite-assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Old firmwares are the whole point; NewApi still guards every call above Android 4.0.
        disable += setOf("OldTargetApi", "ExpiredTargetSdkVersion")
    }
    packaging {
        resources.excludes += setOf("/META-INF/**", "kotlin/**", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// The aerial catalog, copied from the full app at build time rather than kept twice.
val copyAerials by tasks.registering(Copy::class) {
    from(rootProject.file("app/src/main/assets/aerials.json"))
    into(layout.projectDirectory.dir("lite-assets"))
}
tasks.named("preBuild") { dependsOn(copyAerials) }

dependencies {
    testImplementation("junit:junit:4.13.2")
}

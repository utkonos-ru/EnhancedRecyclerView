plugins {
    kotlin("multiplatform")
    id("com.android.library")

    `maven-publish`
}

android {
    compileSdk = currentSdk
    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")

    defaultConfig {
        targetSdk = currentSdk
        minSdk = minimumSdk
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    android {
        publishLibraryVariants("release", "debug")
    }
    ios {
        binaries {
            framework {
                baseName = "enhancedRecyclerViewShared"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib", kotlin_version))
            }
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = "com.github.utkonos-online-shop"
                artifactId = "EnhancedRecyclerView-Shared"
                version = "LOCAL"

                artifact("$buildDir/outputs/aar/${project.name}-release.aar")
            }
        }
    }
}
plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")

    `maven-publish`
}

android {
    compileSdk = currentSdk
    buildToolsVersion = buildTools

    defaultConfig {
        minSdk = minimumSdk
        targetSdk = currentSdk

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFile("consumer-rules.pro")
    }

    buildFeatures {
        dataBinding = true
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
}

dependencies {
    implementation(kotlin("stdlib", kotlin_version))
    implementation(kotlin("reflect", kotlin_version))
    implementation("androidx.core:core-ktx:$core_ktx_version")
    implementation("androidx.appcompat:appcompat:$appcompat_version")
    implementation("com.google.android.material:material:$material_version")
    implementation("io.reactivex.rxjava2:rxjava:$rxjava_version")
    implementation("io.reactivex.rxjava2:rxandroid:$rxandroid_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$kotlinx_coroutines_version")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                groupId = "com.github.utkonos-online-shop"
                artifactId = "EnhancedRecyclerView"
                version = "LOCAL"

                artifact("$buildDir/outputs/aar/${project.name}-release.aar")
            }
        }
    }
}
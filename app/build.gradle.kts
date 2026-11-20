plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.thedirepack.constructioncalc"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.thedirepack.constructioncalc"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:measure"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    // Tools. Add new tool modules here and in ToolRegistry.
    implementation(project(":feature:projects"))
    implementation(project(":feature:footinch"))
    implementation(project(":feature:panels"))
    implementation(project(":feature:conduitbending"))
    implementation(project(":feature:conduitfill"))
    implementation(project(":feature:voltagedrop"))
    implementation(project(":feature:pullplanner"))
    implementation(project(":feature:reellength"))
    implementation(project(":feature:conversions"))
    implementation(project(":feature:vfdsizing"))
    implementation(project(":feature:wellpump"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

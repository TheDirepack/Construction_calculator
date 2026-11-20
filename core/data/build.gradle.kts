plugins {
    id("cc.android.library")
}

android {
    namespace = "com.thedirepack.constructioncalc.core.data"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}

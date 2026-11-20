plugins {
    id("cc.android.library")
}

android {
    namespace = "com.thedirepack.constructioncalc.core.ui"
}

dependencies {
    api(project(":core:data"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
}

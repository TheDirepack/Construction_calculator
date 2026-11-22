plugins {
    id("cc.android.feature")
}

android {
    namespace = "com.thedirepack.constructioncalc.feature.drawings"
}

dependencies {
    implementation(project(":core:plans"))
    // Reads the drawn lines of vector sheets for snapping.
    implementation(libs.pdfbox.android)
    // Finds lines on scanned and photographed sheets.
    implementation(libs.opencv.android)
}

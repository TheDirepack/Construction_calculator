plugins {
    id("cc.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // OpenCV's Java API. The app supplies the Android build (same release); tests use the desktop
    // build, which carries its own native library.
    compileOnly(libs.opencv.desktop)
    testImplementation(libs.opencv.desktop)
    testImplementation(libs.pdfbox.desktop)
}

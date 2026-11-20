plugins {
    id("cc.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:measure"))
    api(project(":core:electrical"))
    api(libs.kotlinx.serialization.json)
}

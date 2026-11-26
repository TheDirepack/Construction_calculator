plugins {
    id("cc.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:measure"))
    api(project(":core:electrical"))
    api(libs.kotlinx.serialization.json)
}

// The device catalog has one source of truth, kept with the symbol detector that is trained on it.
// The build copies it onto this module's classpath (DeviceCatalog.RESOURCE); there is no checked-in
// duplicate to drift. DeviceCatalogTest checks the packaged copy matches the source byte for byte.
val syncDeviceCatalog = tasks.register<Sync>("syncDeviceCatalog") {
    from(layout.projectDirectory.file("../../ml/symbol-detector/annotator/electrical_device_catalog.json")) {
        into("device-catalog")
    }
    into(layout.buildDirectory.dir("generated/device-catalog"))
}

sourceSets.named("main") {
    resources.srcDir(syncDeviceCatalog)
}

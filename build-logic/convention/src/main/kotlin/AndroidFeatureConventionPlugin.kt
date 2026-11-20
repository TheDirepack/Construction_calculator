import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * A tool module ("feature"). Gets the shared engine, project model, app services and UI kit, so a
 * new tool only needs its own domain logic and screen. See docs/ARCHITECTURE.md.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("cc.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        dependencies {
            "implementation"(project(":core:measure"))
            "implementation"(project(":core:model"))
            "implementation"(project(":core:electrical"))
            "implementation"(project(":core:data"))
            "implementation"(project(":core:ui"))
            "implementation"(libs.lib("androidx-activity-compose"))
            "implementation"(libs.lib("androidx-core-ktx"))
            "implementation"(libs.lib("androidx-lifecycle-viewmodel-compose"))
            "implementation"(libs.lib("androidx-lifecycle-runtime-compose"))
            "implementation"(libs.lib("kotlinx-serialization-json"))
        }
    }
}

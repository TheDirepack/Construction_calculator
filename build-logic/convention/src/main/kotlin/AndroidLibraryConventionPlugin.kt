import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Android library with Jetpack Compose. Kotlin comes from AGP's built-in Kotlin support. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            compileSdk = Sdk.COMPILE
            compileSdkMinor = Sdk.COMPILE_MINOR
            defaultConfig.minSdk = Sdk.MIN
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildFeatures.compose = true
            testOptions.unitTests.isIncludeAndroidResources = true
        }

        dependencies {
            val bom = libs.lib("androidx-compose-bom")
            "implementation"(platform(bom))
            "implementation"(libs.lib("androidx-compose-ui"))
            "implementation"(libs.lib("androidx-compose-ui-graphics"))
            "implementation"(libs.lib("androidx-compose-ui-tooling-preview"))
            "implementation"(libs.lib("androidx-compose-material3"))
            "debugImplementation"(libs.lib("androidx-compose-ui-tooling"))
            "testImplementation"(libs.lib("junit"))
        }
    }
}

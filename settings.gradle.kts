pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ConstructionCalculator"

include(":app")

// Shared foundations (see docs/ARCHITECTURE.md)
include(":core:measure") // pure Kotlin: exact fractions, measurements, calculator engine
include(":core:model")   // pure Kotlin: projects, runs, items, result values, file format
include(":core:electrical") // pure Kotlin: NEC tables & electrical formulas shared by tools
include(":core:plans")   // pure Kotlin: line maps, snapping and line finding for drawings (OpenCV API)
include(":core:data")    // Android: repositories (projects, settings, recent results), share/import
include(":core:ui")      // Android: theme, tool API, shared inputs (MeasureField…), keypad

// Tools: one module each
include(":feature:projects")
include(":feature:drawings")
include(":feature:footinch")
include(":feature:panels")
include(":feature:conduitbending")
include(":feature:conduitfill")
include(":feature:voltagedrop")
include(":feature:pullplanner")
include(":feature:reellength")
include(":feature:conversions")
include(":feature:vfdsizing")
include(":feature:formulas")
include(":feature:reference")
include(":feature:wellpump")

pluginManagement {
    repositories {
        google()
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

rootProject.name = "MangaWorld"
include(":app")
// Device measure gate for the Stage-2 perf work (U-26/U-27). See
// tmp/review/stage2/perf.md §"Measure spec" and benchmark/README.md.
include(":benchmark")

pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral(); google { content { includeGroup("androidx.annotation") } } } }
rootProject.name = "time-whisper"

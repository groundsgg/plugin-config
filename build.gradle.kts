plugins { id("gg.grounds.base-conventions") version "0.5.1" }

group = "gg.grounds"

val semanticVersion =
    Regex(
        "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:-(?:0|[1-9]\\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
    )
val versionFileContents = file("version.txt").readText()
val versionFromFile = versionFileContents.removeSuffix("\n")

check(versionFileContents == versionFromFile || versionFileContents == "$versionFromFile\n") {
    "version.txt may contain only one optional trailing newline"
}

check(semanticVersion.matches(versionFromFile)) {
    "version.txt must contain a strict SemVer version"
}

val resolvedVersion = providers.gradleProperty("versionOverride").orNull ?: versionFromFile

check(semanticVersion.matches(resolvedVersion)) {
    "versionOverride must contain a strict SemVer version"
}

version = resolvedVersion

gradle.projectsEvaluated {
    subprojects {
        // Convention plugins assign local-SNAPSHOT while they apply. Set the
        // release-controlled root version only after every convention has run.
        version = rootProject.version
    }
}

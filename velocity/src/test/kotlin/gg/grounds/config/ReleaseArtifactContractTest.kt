package gg.grounds.config

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseArtifactContractTest {
    private val root =
        generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .first { it.resolve("settings.gradle.kts").exists() }

    @Test
    fun `release version is strict SemVer and controls Gradle project version`() {
        val version = strictVersion(root.resolve("version.txt").readText())

        assertEquals(version, System.getProperty("releaseVersion"))
    }

    @Test
    fun `shaded Velocity artifact carries the release version and plugin identity`() {
        val version = strictVersion(root.resolve("version.txt").readText())
        val archive = root.resolve("velocity/build/libs/plugin-config-velocity.jar")
        assertTrue(archive.exists(), "shadowJar did not produce $archive")
        ZipFile(archive.toFile()).use { jar ->
            val descriptor = jar.getEntry("velocity-plugin.json")
            assertTrue(descriptor != null, "shaded artifact must contain velocity-plugin.json")
            val metadata = jar.getInputStream(descriptor).bufferedReader().readText()
            assertContains(metadata, "\"id\":\"plugin-config\"")
            assertContains(metadata, "\"version\":\"$version\"")
        }
    }

    @Test
    fun `Docker release path is a secret-backed data-only Velocity artifact`() {
        val dockerfile = root.resolve("Dockerfile").readText()
        val copySources =
            dockerfile
                .lineSequence()
                .filter { it.startsWith("COPY ") && !it.startsWith("COPY --from=") }
                .toList()

        assertEquals(
            listOf(
                "COPY gradle/ gradle/",
                "COPY gradlew settings.gradle.kts build.gradle.kts version.txt ./",
                "COPY common/ common/",
                "COPY velocity/ velocity/",
            ),
            copySources,
        )
        assertContains(dockerfile, "FROM eclipse-temurin:25-jdk AS build")
        assertContains(dockerfile, "mkdir -p paper example-paper example-velocity")
        assertContains(dockerfile, "--mount=type=secret,id=github_token,required=true")
        assertContains(dockerfile, ":velocity:shadowJar")
        assertContains(dockerfile, "COPY --from=build /out/plugin.jar /jar/plugin.jar")
        assertFalse(Regex("(?m)^(ENTRYPOINT|CMD)\\b").containsMatchIn(dockerfile))
    }

    @Test
    fun `release publishes Maven and OCI artifacts from the Release Please version`() {
        val release = root.resolve(".github/workflows/release.yml").readText()
        val releasePlease = root.resolve("release-please-config.json").readText()

        assertContains(release, "tags: [v*]")
        assertContains(release, "maven:")
        assertContains(release, "docker:")
        assertContains(release, "groundsgg/.github/.github/workflows/gradle-publish.yml@main")
        assertContains(
            release,
            "groundsgg/.github/.github/workflows/docker-gradle-build-push.yml@main",
        )
        assertContains(release, "PACKAGES_TOKEN: ${'$'}{{ secrets.GROUNDS_PACKAGES_TOKEN }}")
        assertContains(release, "contents: write")
        assertContains(release, "packages: write")
        assertContains(releasePlease, "\"extra-files\": [\"version.txt\"]")
        assertContains(releasePlease, "\"include-v-in-tag\": true")
    }

    private fun strictVersion(contents: String): String {
        val version = contents.removeSuffix("\n")
        assertTrue(contents == version || contents == "$version\n")
        assertTrue(
            Regex(
                    "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:-(?:0|[1-9]\\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\\.(?:0|[1-9]\\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
                )
                .matches(version)
        )
        return version
    }
}

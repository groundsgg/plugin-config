# Library Gradle Plugin Protobuf Relocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Centralize protobuf relocation for final Paper and Velocity plugin artifacts in `../../library-gradle-plugin` so plugin projects stop depending on Paper's bundled `protobuf-java` runtime.

**Architecture:** Add a small relocation helper in the shared shaded-plugin convention layer and expose an opt-in extension for packages that must be relocated in final plugin jars. Keep `grpc-conventions` responsible for code generation/runtime alignment, and keep relocation at the final packaging boundary in `paper-base-conventions` so library modules such as `common` stay unshaded. Verify behavior with Gradle TestKit functional tests that assert relocated plugin jars no longer expose `com/google/protobuf/*`.

**Tech Stack:** Gradle Kotlin DSL precompiled script plugins, Shadow plugin, Gradle TestKit, Kotlin test/JUnit, protobuf Gradle plugin.

---

## File Structure

**Implementation files**
- Modify: `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/paper-base-conventions.gradle.kts`
  Adds the shared relocation hook for shaded plugin artifacts.
- Modify: `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/grpc-conventions.gradle.kts`
  Registers protobuf relocation intent for projects that generate protobuf classes.
- Modify: `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/paper-conventions.gradle.kts`
  Keeps Paper-specific behavior unchanged except for inheriting the new shared packaging behavior.
- Modify: `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/velocity-conventions.gradle.kts`
  Keeps Velocity-specific behavior unchanged except for inheriting the new shared packaging behavior.

**Test files**
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/kotlin/gg/grounds/PaperPluginRelocationFunctionalTest.kt`
  Verifies Paper plugin jars relocate protobuf when gRPC conventions are applied.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/kotlin/gg/grounds/VelocityPluginRelocationFunctionalTest.kt`
  Verifies Velocity plugin jars relocate protobuf when gRPC conventions are applied.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/paper-grpc-plugin/settings.gradle.kts`
  Minimal TestKit fixture plugin project.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/paper-grpc-plugin/build.gradle.kts`
  Applies `paper-conventions` and `grpc-conventions` in one module.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/paper-grpc-plugin/src/main/resources/plugin.yml`
  Minimal Paper plugin descriptor.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/paper-grpc-plugin/src/main/proto/sample.proto`
  Minimal protobuf schema for generated Java classes.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/velocity-grpc-plugin/settings.gradle.kts`
  Minimal TestKit fixture plugin project.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/velocity-grpc-plugin/build.gradle.kts`
  Applies `velocity-conventions` and `grpc-conventions` in one module.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/velocity-grpc-plugin/src/main/kotlin/example/ExampleVelocityPlugin.kt`
  Minimal Velocity plugin implementation.
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/velocity-grpc-plugin/src/main/proto/sample.proto`
  Minimal protobuf schema for generated Java classes.

**Build and documentation files**
- Modify: `/home/lukas/grounds/library-gradle-plugin/build.gradle.kts`
  Adds explicit TestKit and test framework dependencies if needed by the new functional tests.
- Modify: `/home/lukas/grounds/library-gradle-plugin/README.md`
  Documents that final Paper/Velocity plugin artifacts relocate protobuf when `grpc-conventions` is combined with plugin packaging conventions.

---

### Task 1: Add Functional Test Harness For Convention Plugins

**Files:**
- Modify: `/home/lukas/grounds/library-gradle-plugin/build.gradle.kts`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/kotlin/gg/grounds/PaperPluginRelocationFunctionalTest.kt`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/kotlin/gg/grounds/VelocityPluginRelocationFunctionalTest.kt`

- [ ] **Step 1: Write the failing Paper functional test**

```kotlin
package gg.grounds

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

class PaperPluginRelocationFunctionalTest {
    @Test
    fun `paper plugin relocates protobuf when grpc conventions are applied`() {
        val projectDir = copyFixture("paper-grpc-plugin")

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("shadowJar", "--stacktrace")
            .forwardOutput()
            .build()

        val jarPath = projectDir.resolve("build/libs/test-plugin.jar")
        ZipFile(jarPath.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toSet()

            assertFalse(
                entries.any { it.startsWith("com/google/protobuf/") },
                "shadow jar still exposes com/google/protobuf classes",
            )
            assertTrue(
                entries.any { it.startsWith("gg/grounds/shaded/protobuf/") },
                "shadow jar does not contain relocated protobuf classes",
            )
        }
    }

    private fun copyFixture(name: String): Path {
        val source = Path.of("src/test/resources/test-projects").resolve(name)
        val target = createTempDirectory("paper-grpc-plugin")
        source.copyToRecursively(target, followLinks = false)
        return target
    }
}
```

- [ ] **Step 2: Write the failing Velocity functional test**

```kotlin
package gg.grounds

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

class VelocityPluginRelocationFunctionalTest {
    @Test
    fun `velocity plugin relocates protobuf when grpc conventions are applied`() {
        val projectDir = copyFixture("velocity-grpc-plugin")

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("shadowJar", "--stacktrace")
            .forwardOutput()
            .build()

        val jarPath = projectDir.resolve("build/libs/test-plugin.jar")
        ZipFile(jarPath.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.toSet()

            assertFalse(
                entries.any { it.startsWith("com/google/protobuf/") },
                "shadow jar still exposes com/google/protobuf classes",
            )
            assertTrue(
                entries.any { it.startsWith("gg/grounds/shaded/protobuf/") },
                "shadow jar does not contain relocated protobuf classes",
            )
        }
    }

    private fun copyFixture(name: String): Path {
        val source = Path.of("src/test/resources/test-projects").resolve(name)
        val target = createTempDirectory("velocity-grpc-plugin")
        source.copyToRecursively(target, followLinks = false)
        return target
    }
}
```

- [ ] **Step 3: Add test dependencies required by the new functional tests**

```kotlin
dependencies {
    implementation(
        "org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:$embeddedKotlinVersion"
    )
    implementation(
        "org.jetbrains.kotlin.kapt:org.jetbrains.kotlin.kapt.gradle.plugin:$embeddedKotlinVersion"
    )
    implementation("com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:8.4.0")
    implementation("com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:9.4.1")
    implementation(
        "com.github.gmazzo.buildconfig:com.github.gmazzo.buildconfig.gradle.plugin:6.0.9"
    )
    implementation("com.google.protobuf:com.google.protobuf.gradle.plugin:0.9.6")

    testImplementation(gradleTestKit())
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Create the first red run**

Run: `./gradlew test --tests gg.grounds.PaperPluginRelocationFunctionalTest --tests gg.grounds.VelocityPluginRelocationFunctionalTest`

Expected:
- Both tests execute `shadowJar`
- At least one test fails with `shadow jar still exposes com/google/protobuf classes`

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/test
git commit -m "test: add functional coverage for protobuf relocation"
```

---

### Task 2: Add Minimal Test Fixture Projects

**Files:**
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/paper-grpc-plugin/settings.gradle.kts`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/paper-grpc-plugin/build.gradle.kts`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/paper-grpc-plugin/src/main/resources/plugin.yml`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/paper-grpc-plugin/src/main/proto/sample.proto`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/velocity-grpc-plugin/settings.gradle.kts`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/velocity-grpc-plugin/build.gradle.kts`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/velocity-grpc-plugin/src/main/kotlin/example/ExampleVelocityPlugin.kt`
- Create: `/home/lukas/grounds/library-gradle-plugin/src/test/resources/test-projects/velocity-grpc-plugin/src/main/proto/sample.proto`

- [ ] **Step 1: Create the Paper fixture settings file**

```kotlin
rootProject.name = "test-plugin"
```

- [ ] **Step 2: Create the Paper fixture build file**

```kotlin
plugins {
    id("gg.grounds.base-conventions")
    id("gg.grounds.paper-conventions")
    id("gg.grounds.grpc-conventions")
}

version = "test"

dependencies {
    implementation("io.grpc:grpc-netty-shaded:1.80.0")
}
```

- [ ] **Step 3: Create the Paper fixture plugin descriptor**

```yaml
name: test-plugin
version: test
main: example.ExamplePaperPlugin
api-version: "1.21"
```

- [ ] **Step 4: Create the shared Paper fixture proto**

```proto
syntax = "proto3";

option java_multiple_files = true;
option java_package = "example.proto";

message SampleReply {
  string message = 1;
}
```

- [ ] **Step 5: Create the Velocity fixture settings file**

```kotlin
rootProject.name = "test-plugin"
```

- [ ] **Step 6: Create the Velocity fixture build file**

```kotlin
plugins {
    id("gg.grounds.base-conventions")
    id("gg.grounds.velocity-conventions")
    id("gg.grounds.grpc-conventions")
}

version = "test"

dependencies {
    implementation("io.grpc:grpc-netty-shaded:1.80.0")
}
```

- [ ] **Step 7: Create the minimal Velocity plugin class**

```kotlin
package example

import com.google.inject.Inject
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger

@Plugin(
    id = "test-plugin",
    name = "test-plugin",
    version = "test",
)
class ExampleVelocityPlugin
@Inject
constructor(
    @Suppress("unused") private val proxyServer: ProxyServer,
    @Suppress("unused") private val logger: Logger,
)
```

- [ ] **Step 8: Create the shared Velocity fixture proto**

```proto
syntax = "proto3";

option java_multiple_files = true;
option java_package = "example.proto";

message SampleReply {
  string message = 1;
}
```

- [ ] **Step 9: Run the red tests again**

Run: `./gradlew test --tests gg.grounds.PaperPluginRelocationFunctionalTest --tests gg.grounds.VelocityPluginRelocationFunctionalTest`

Expected:
- Fixture projects compile and build their shadow jars
- Tests still fail on raw `com/google/protobuf` exposure

- [ ] **Step 10: Commit**

```bash
git add src/test/resources/test-projects
git commit -m "test: add fixture plugin projects for relocation coverage"
```

---

### Task 3: Add Shared Relocation Infrastructure At The Plugin Packaging Boundary

**Files:**
- Modify: `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/paper-base-conventions.gradle.kts`

- [ ] **Step 1: Add a typed extension that records relocation requests**

```kotlin
package gg.grounds

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.kotlin.dsl.create

abstract class GroundsRelocationExtension {
    @get:Input abstract val packages: ListProperty<String>
}

val groundsRelocation = extensions.create<GroundsRelocationExtension>("groundsRelocation").apply {
    packages.convention(emptyList())
}
```
```
Note: keep the class in a dedicated Kotlin source file if precompiled script plugin compilation rejects top-level abstract types inside the script. If that happens, create `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/GroundsRelocationExtension.kt` with the same class body and import it from the script.
```

- [ ] **Step 2: Wire `shadowJar` to relocate every registered package into the shared shaded namespace**

```kotlin
tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("${rootProject.name}-${project.name}")
    archiveClassifier.set("")
    archiveVersion.set("")

    doFirst {
        groundsRelocation.packages.get().distinct().forEach { pkg ->
            val shaded = "gg.grounds.shaded.${pkg}"
            relocate(pkg, shaded)
        }
    }
}
```

- [ ] **Step 3: Keep build wiring unchanged and verify script compilation**

Run: `./gradlew compileKotlin`

Expected:
- `paper-base-conventions.gradle.kts` compiles
- No consumer behavior changes yet because no convention registers relocation packages

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/gg/grounds/paper-base-conventions.gradle.kts src/main/kotlin/gg/grounds/GroundsRelocationExtension.kt
git commit -m "feat: add shared relocation hook for shaded plugin jars"
```

---

### Task 4: Have gRPC Conventions Register Protobuf Relocation Intent

**Files:**
- Modify: `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/grpc-conventions.gradle.kts`

- [ ] **Step 1: Register protobuf relocation when the shared relocation extension exists**

```kotlin
plugins {
    id("gg.grounds.kotlin-conventions")
    id("com.google.protobuf")
}

val grpcVersion = "1.80.0"
val protobufVersion = "4.34.1"

extensions.findByType(GroundsRelocationExtension::class.java)?.let { extension ->
    extension.packages.add("com.google.protobuf")
}
```

- [ ] **Step 2: Keep code generation/runtime alignment explicit**

```kotlin
dependencies {
    compileOnly("com.google.protobuf:protobuf-java:${protobufVersion}")
    implementation("io.grpc:grpc-protobuf:${grpcVersion}")
    implementation("io.grpc:grpc-stub:${grpcVersion}")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobufVersion" }

    plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion" } }

    generateProtoTasks { all().forEach { task -> task.plugins { create("grpc") } } }
}
```

- [ ] **Step 3: Run focused functional tests**

Run: `./gradlew test --tests gg.grounds.PaperPluginRelocationFunctionalTest --tests gg.grounds.VelocityPluginRelocationFunctionalTest`

Expected:
- Both tests pass
- Built fixture jars contain `gg/grounds/shaded/protobuf/*`
- Built fixture jars no longer contain `com/google/protobuf/*`

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/gg/grounds/grpc-conventions.gradle.kts
git commit -m "feat: register protobuf relocation for grpc plugin projects"
```

---

### Task 5: Verify Paper And Velocity Convention Behavior Stays Stable

**Files:**
- Modify: `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/paper-conventions.gradle.kts`
- Modify: `/home/lukas/grounds/library-gradle-plugin/src/main/kotlin/gg/grounds/velocity-conventions.gradle.kts`

- [ ] **Step 1: Review Paper convention for unintended packaging changes**

```kotlin
package gg.grounds

plugins { id("gg.grounds.paper-base-conventions") }

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

dependencies { compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") }

val pluginVersion: Any = project.version

tasks.withType<ProcessResources> {
    inputs.property("version", pluginVersion)
    filesMatching(listOf("**/plugin.yml")) { expand(mapOf("VERSION" to pluginVersion)) }
}
```

- [ ] **Step 2: Review Velocity convention for unintended packaging changes**

```kotlin
package gg.grounds

import com.github.gmazzo.buildconfig.BuildConfigExtension

plugins {
    id("com.github.gmazzo.buildconfig")
    id("gg.grounds.paper-base-conventions")
}

repositories { maven("https://repo.papermc.io/repository/maven-public/") }

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
    kapt("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")
}

configure<BuildConfigExtension> {
    className("BuildInfo")
    packageName("gg.grounds")
    useKotlinOutput()
    buildConfigField("String", "VERSION", "\"${project.version}\"")
}
```

- [ ] **Step 3: Only change these files if a compile issue appears**

Run: `./gradlew build`

Expected:
- No code changes required in `paper-conventions` or `velocity-conventions`
- Build passes with the new functional tests

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/gg/grounds/paper-conventions.gradle.kts src/main/kotlin/gg/grounds/velocity-conventions.gradle.kts
git commit -m "chore: verify plugin conventions remain stable with relocation support"
```

---

### Task 6: Document The Packaging Contract

**Files:**
- Modify: `/home/lukas/grounds/library-gradle-plugin/README.md`

- [ ] **Step 1: Document the new behavior in the convention descriptions**

```markdown
- `grpc-conventions`: Common configuration for gRPC. When combined with `paper-conventions` or `velocity-conventions`, protobuf runtime classes are relocated into the final shaded plugin jar to avoid conflicts with server-bundled protobuf versions.
- `paper-base-conventions`: Common configuration for velocity and paper plugins, including final shaded jar packaging and relocation hooks used by gRPC-enabled plugin projects.
```

- [ ] **Step 2: Add a short troubleshooting note for protobuf mismatches**

```markdown
### Protobuf runtime mismatches on server platforms

Paper bundles its own `protobuf-java` runtime. Projects that apply both `gg.grounds.grpc-conventions` and a shaded plugin convention (`gg.grounds.paper-conventions` or `gg.grounds.velocity-conventions`) relocate protobuf classes into the final plugin jar so generated protobuf code does not bind against the server's bundled runtime.
```

- [ ] **Step 3: Run formatting and docs build checks**

Run: `./gradlew spotlessApply build`

Expected:
- README changes are committed with no formatting regressions
- Full plugin build and functional test suite pass

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: describe protobuf relocation in plugin conventions"
```

---

## Self-Review

**Spec coverage**
- Centralized location: handled in `paper-base-conventions` rather than per consumer plugin.
- Correct scope: only final Paper/Velocity plugin artifacts participate because they inherit `paper-base-conventions`; `common`/library modules do not.
- gRPC/protobuf integration: handled by `grpc-conventions` registering protobuf relocation intent.
- Verification: covered with new TestKit functional tests for both Paper and Velocity.
- Documentation: covered in README updates.

**Placeholder scan**
- No `TODO`, `TBD`, or “handle appropriately” placeholders remain.
- Commands are concrete.
- File paths are explicit.

**Type consistency**
- Shared extension name: `GroundsRelocationExtension`.
- Shared extension instance: `groundsRelocation`.
- Relocated namespace: `gg.grounds.shaded.protobuf`.
- Verification tests assert the same namespace for both plugin types.

Plan complete and saved to `docs/superpowers/plans/2026-04-19-library-gradle-plugin-protobuf-relocation.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?

import com.github.gmazzo.buildconfig.BuildConfigExtension
import org.gradle.api.tasks.testing.Test

plugins { id("gg.grounds.velocity-conventions") }

configure<BuildConfigExtension> {
    buildConfigFields.remove(buildConfigFields.getByName("VERSION"))
    buildConfigField("String", "VERSION", "\"${rootProject.version}\"")
}

dependencies {
    implementation(project(":common"))
    testImplementation(kotlin("test"))
}

tasks.named<Test>("test") {
    dependsOn("shadowJar")
    systemProperty("releaseVersion", rootProject.version.toString())
}

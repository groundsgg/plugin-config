plugins { id("gg.grounds.grpc-conventions") }

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
    mavenCentral()
}

dependencies {
    protobuf("gg.grounds:library-grpc-contracts-config:feat-config-SNAPSHOT")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    implementation("io.nats:jnats:2.25.1")
    implementation("tools.jackson.core:jackson-databind:3.0.4")
    implementation("tools.jackson.module:jackson-module-kotlin:3.0.4")
}

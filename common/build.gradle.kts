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
    protobuf("gg.grounds:library-grpc-contracts-config:0.2.0")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    implementation("com.google.protobuf:protobuf-java:4.34.1")
    implementation("io.nats:jnats:2.25.2")
    implementation("tools.jackson.core:jackson-databind:3.1.2")
    implementation("tools.jackson.module:jackson-module-kotlin:3.1.2")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation(kotlin("test"))
}

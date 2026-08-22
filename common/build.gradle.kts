plugins { id("gg.grounds.kotlin-conventions") }

repositories { mavenCentral() }

dependencies {
    compileOnly("org.slf4j:slf4j-api:2.0.18")
    implementation("io.nats:jnats:2.25.2")
    // service-config answers REST; the client parses its JSON with the same
    // Jackson 3 line the snapshot applier already uses.
    implementation("tools.jackson.core:jackson-databind:3.1.2")
    implementation("tools.jackson.module:jackson-module-kotlin:3.2.2")
    testImplementation("org.slf4j:slf4j-api:2.0.18")
    testImplementation(kotlin("test"))
}

plugins { id("gg.grounds.paper-conventions") }

dependencies {
    implementation(project(":common"))
    implementation("io.grpc:grpc-netty-shaded:1.79.0")
    implementation("io.grpc:grpc-stub:1.79.0")
}

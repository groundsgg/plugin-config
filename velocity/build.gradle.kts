plugins { id("gg.grounds.velocity-conventions") }

dependencies {
    implementation(project(":common"))
    implementation("io.grpc:grpc-netty-shaded:1.81.0")
    implementation("io.grpc:grpc-stub:1.81.0")
}

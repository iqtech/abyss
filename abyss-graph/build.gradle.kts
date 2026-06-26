dependencies {
    api(project(":abyss-dsl"))
    implementation(libs.hazelcast)
    implementation(libs.slf4j.api)
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
}

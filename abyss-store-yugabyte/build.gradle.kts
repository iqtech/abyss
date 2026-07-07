dependencies {
    implementation(project(":abyss-store-api"))
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.datastax.driver)
    implementation(libs.slf4j.api)
    testImplementation(kotlin("test"))
}

dependencies {
    implementation(project(":abyss-store-api"))
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.datastax.driver)
}

dependencies {
    implementation(project(":abyss-store-api"))
    implementation(libs.hikari)
    implementation(libs.yugabyte.jdbc)
    implementation(libs.datastax.driver)
    implementation(libs.slf4j.api)
    testImplementation(kotlin("test"))
    // Stock pgjdbc for tests that open raw jdbc:postgresql:// connections (ConnectionTest,
    // LoadTest raw datasources, YsqlPartitionScanFeasibilityTest). The smart driver above is
    // fully relocated to com.yugabyte.* / jdbc:yugabytedb:, so the two never collide.
    testImplementation(libs.postgresql)
}

tasks.test {
    if (project.hasProperty("perf")) systemProperty("perf", "true")
    if (project.hasProperty("cluster")) systemProperty("cluster", "true")
}

dependencies {
    api(project(":abyss-dsl"))
    implementation(libs.hazelcast)
    implementation(libs.slf4j.api)
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.log4j.core)
    testRuntimeOnly(libs.log4j.slf4j2.impl)
}

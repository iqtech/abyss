dependencies {
    api(project(":abyss-store-api"))
    api(libs.arrow.core)
    api(libs.kotlinx.coroutines)
    api(libs.hazelcast)
    implementation(kotlin("reflect"))
}

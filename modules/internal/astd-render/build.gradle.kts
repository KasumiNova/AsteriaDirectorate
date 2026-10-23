plugins {
    kotlin("jvm")
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":astd-api"))
    implementation(project(":astd-api-render"))
    implementation(project(":astd-impl"))

    testImplementation(testFixtures(project(":astd-impl")))
}

rootProject.tasks.named<Jar>("jar") {
    from(sourceSets.main.get().output)
    dependsOn(tasks.named("classes"))
}

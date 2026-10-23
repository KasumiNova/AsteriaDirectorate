plugins {
    kotlin("jvm")
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":astd-api"))
    implementation(project(":astd-impl"))
    implementation(project(":astd-ui"))
    implementation(project(":astd-combat"))

    testImplementation(testFixtures(project(":astd-csv")))
}

rootProject.tasks.named<Jar>("jar") {
    from(sourceSets.main.get().output)
    dependsOn(tasks.named("classes"))
}

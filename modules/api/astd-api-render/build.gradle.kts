plugins {
    kotlin("jvm")
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":astd-api"))
}

rootProject.tasks.named<Jar>("jar") {
    from(sourceSets.main.get().output)
    dependsOn(tasks.named("classes"))
}

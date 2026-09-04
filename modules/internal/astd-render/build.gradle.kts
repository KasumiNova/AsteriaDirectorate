plugins {
    kotlin("jvm")
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":astd-api"))
    implementation(project(":astd-api-render"))
    implementation(project(":astd-impl"))

    // 跨模块共享测试桩（WarnCapture/stubShip/stubWeapon，定义在 astd-impl testFixtures）。
    testImplementation(testFixtures(project(":astd-impl")))
}

// 装配：向根工程 mod 主 jar 贡献本模块产物（SDG 打包单点绑定根工程 jar task）。
rootProject.tasks.named<Jar>("jar") {
    from(sourceSets.main.get().output)
    dependsOn(tasks.named("classes"))
}

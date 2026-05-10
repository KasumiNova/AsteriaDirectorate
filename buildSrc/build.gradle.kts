plugins {
    `kotlin-dsl`
}

repositories {
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
}

dependencies {
    implementation("org.json:json:20240303")
    implementation(kotlin("gradle-plugin"))
    implementation(gradleApi())
}

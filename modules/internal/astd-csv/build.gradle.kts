plugins {
    kotlin("jvm")
    // testFixtures 暴露 CsvTestUtil 给其它模块/根工程的测试（仓库级 CSV 数据校验共用）。
    `java-test-fixtures`
}

import org.gradle.api.GradleException

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

repositories {
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("io.github.classgraph:classgraph:4.8.179")
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.register<JavaExec>("generateSsCsv") {
    group = "starsector"
    description = "Scan ss-csv entries (Kotlin objects) and generate Starsector CSV files into build/generated/ss-csv/."

    dependsOn(tasks.named("classes"))

    // Kotlin top-level main
    mainClass.set("cn.kasuminova.astd.sscsv.gen.SsCsvGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath

    // I18n: 默认 zh-cn；可通过 -Psscsv.locale=en-us 切换。
    systemProperties["sscsv.locale"] = (project.findProperty("sscsv.locale") as? String)?.trim()?.ifBlank { "zh-cn" } ?: "zh-cn"

    val outDir = rootProject.layout.buildDirectory.dir("generated/ss-csv").get().asFile
    val schemaDir = rootProject.projectDir.resolve("tools/_schema_headers")

    args(
        "--out", outDir.absolutePath,
        "--schema", schemaDir.absolutePath,
        "--scan", "cn.kasuminova.astd.sscsv.entries.catalog",
        "--comment", "inline"
    )
}

tasks.register<JavaExec>("writeSsCsvToContents") {
    group = "starsector"
    description = "Generate Starsector CSV files directly into contents/data (OVERWRITES)."

    dependsOn(tasks.named("classes"))

    mainClass.set("cn.kasuminova.astd.sscsv.gen.SsCsvGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath

    systemProperties["sscsv.locale"] = (project.findProperty("sscsv.locale") as? String)?.trim()?.ifBlank { "zh-cn" } ?: "zh-cn"

    doFirst {
        val force = (project.findProperty("ssCsvForce") as? String)?.trim()?.lowercase()
        if (force != "true") {
            throw GradleException(
                "Refusing to overwrite contents/. Re-run with -PssCsvForce=true if you're sure."
            )
        }
    }

    val outDir = rootProject.projectDir.resolve("contents")
    val schemaDir = rootProject.projectDir.resolve("tools/_schema_headers")

    args(
        "--out", outDir.absolutePath,
        "--schema", schemaDir.absolutePath,
        "--scan", "cn.kasuminova.astd.sscsv.entries.catalog",
        "--comment", "inline"
    )
}

val validateSsCsvClassRefs by tasks.registering {
    group = "verification"
    description = "校验 ss-csv 生成/contents 中的脚本类路径是否存在"
    dependsOn(tasks.named("generateSsCsv"))
    // 类产物分散在根工程与各 astd 模块，全量依赖（astd-csv 自身是生成工具，不含游戏类）。
    dependsOn(":classes")
    listOf(
        ":astd-api", ":astd-api-render",
        ":astd-impl", ":astd-ui", ":astd-render", ":astd-combat", ":astd-campaign", ":astd-automation",
    ).forEach { dependsOn("$it:classes") }

    doLast {
        val generatedRoot = rootProject.layout.buildDirectory.dir("generated/ss-csv").get().asFile
        val contentsRoot = rootProject.projectDir.resolve("contents")
        val roots = listOf(generatedRoot, contentsRoot).filter { it.exists() }

        val fileExtensions = setOf(
            "csv", "proj", "wpn", "system", "variant", "ship", "skin",
            "json", "faction", "rules", "txt"
        )
        val regex = Regex("cn\\.kasuminova\\.astd\\.[A-Za-z0-9_.$]+")

        val classNames = linkedSetOf<String>()
        roots.forEach { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in fileExtensions }
                .forEach { file ->
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    regex.findAll(text).forEach { match ->
                        val fqn = match.value
                        // 跳过末段以小写开头的匹配——那是包名而非类名
                        val lastSegment = fqn.substringAfterLast('.')
                        if (lastSegment.firstOrNull()?.isUpperCase() == true) {
                            classNames.add(fqn)
                        }
                    }
                }
        }

        if (classNames.isEmpty()) return@doLast

        val classDirs = (listOf(rootProject) + rootProject.subprojects)
            .filter { it.name != "astd-csv" }
            .flatMap { p ->
                listOf(
                    p.layout.buildDirectory.dir("classes/kotlin/main").get().asFile,
                    p.layout.buildDirectory.dir("classes/java/main").get().asFile,
                )
            }
            .filter { it.exists() }

        fun classExists(fqn: String): Boolean {
            val rel = fqn.replace('.', '/') + ".class"
            return classDirs.any { dir -> dir.resolve(rel).exists() }
        }

        val missing = classNames.filterNot { classExists(it) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "ss-csv class ref validation failed. Missing classes:\n" +
                    missing.joinToString(separator = "\n")
            )
        }
    }
}

tasks.named("writeSsCsvToContents") {
    dependsOn(validateSsCsvClassRefs)
}

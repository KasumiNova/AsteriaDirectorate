plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

import org . gradle . api . GradleException

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

    mainClass.set("cn.kasuminova.astd.sscsv.gen.SsCsvGeneratorKt")
    classpath = sourceSets["main"].runtimeClasspath

    systemProperties["sscsv.locale"] = (project.findProperty("sscsv.locale") as? String)?.trim()?.ifBlank { "zh-cn" } ?: "zh-cn"

    val outDir = rootProject.layout.buildDirectory.dir("generated/ss-csv").get().asFile
    val schemaDir = rootProject.projectDir.resolve("tools/_schema_headers")

    doFirst {
        // 生成器只写不删：条目删除/改名后旧产物会残留并沿部署目录扩散成加载错误，每次生成前清空输出目录。
        delete(outDir)
    }

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

    val outDir = rootProject.projectDir.resolve("contents")
    val schemaDir = rootProject.projectDir.resolve("tools/_schema_headers")
    // 生成器只写不删：每次直写前必须按上一次产物清单清理残留旧产物，否则旧文件会扩散成加载错误。
    val manifestFile = outDir.resolve(".ss-csv-manifest")

    doFirst {
        val force = (project.findProperty("ssCsvForce") as? String)?.trim()?.lowercase()
        if (force != "true") {
            throw GradleException(
                "Refusing to overwrite contents/. Re-run with -PssCsvForce=true if you're sure."
            )
        }
        if (manifestFile.isFile) {
            manifestFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { rel ->
                    val stale = outDir.resolve(rel).normalize()
                    // 清单条目必须落在 contents 内才允许删除。
                    if (stale.startsWith(outDir.normalize()) && stale.isFile) {
                        stale.delete()
                    }
                }
        }
    }

    args(
        "--out", outDir.absolutePath,
        "--schema", schemaDir.absolutePath,
        "--scan", "cn.kasuminova.astd.sscsv.entries.catalog",
        "--comment", "inline",
        "--manifest", manifestFile.absolutePath,
    )
}

val validateSsCsvClassRefs by tasks.registering {
    group = "verification"
    description = "校验 ss-csv 生成/contents 中的脚本类路径是否存在"
    dependsOn(tasks.named("generateSsCsv"))
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

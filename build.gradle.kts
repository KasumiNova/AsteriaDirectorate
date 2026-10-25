import io.github.nanoforged.sdg.GameDependencyMode
import io.github.nanoforged.sdg.LaunchMode
import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

plugins {
    kotlin("jvm") version "2.2.0"
    id("io.github.nanoforged.sectordevgradle.mod") version "0.1.0-SNAPSHOT"
}

group = "cn.kasuminova"

/** `-Pastd.release=true` 排除开发验收内容并生成玩家包。 */
val astdRelease: Boolean =
    providers.gradleProperty("astd.release").map(String::toBooleanStrict).orElse(false).get()

val astdModVersion: String =
    providers.gradleProperty("astd.modVersion").orElse(if (astdRelease) "0.1.3" else "1.0-SNAPSHOT").get()

version = astdModVersion

starsector {
    modId.set("asteria_directorate")
    deployDirName.set("ASTD")
    modName.set("Asteria Directorate")
    author.set("Hikari_Nova, 五六式海猫(五六猫)")
    description.set("高价值赏金(HVB) 扩展包，讲述着一个鼎盛强大的军工复合体在大崩塌后没落的故事。")
    gameVersion.set("0.98a")
    modPlugin.set("cn.kasuminova.astd.AsteriaDirectoratePlugin")
    dependency("lw_lazylib", "LazyLib")
    dependency("MagicLib", "MagicLib")
    dependency("shaderLib", "GraphicsLib")
    dependency("BoxUtil", "zz BoxUtil")
    gameDependencyMode.set(GameDependencyMode.GAME_DIR)
    gameDir.fileValue(file(providers.gradleProperty("starsector.gameDir").get()))
    launchMode.set(LaunchMode.NANOFORGE)
    decompilerVersion.set(providers.gradleProperty("decompiler.version").orElse("1.9.3"))
}

// 本地 runGame 默认开启 SSOptimizer 调试端，可用 -Pastd.ssoDebug=false 关闭。
val astdSsoDebug: Boolean =
    providers.gradleProperty("astd.ssoDebug").map(String::toBooleanStrict).orElse(true).get()

tasks.named<JavaExec>("runGame") {
    if (astdSsoDebug) {
        jvmArgs("-Dssoptimizer.debug.enabled=true")
    }
}

repositories {
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
}

dependencies {
    implementation(project(":astd-campaign"))
    implementation(project(":astd-combat"))
    implementation(project(":astd-impl"))

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":astd-csv")))
    testImplementation("org.mockito:mockito-core:5.5.0")
    compileOnly("org.ow2.asm:asm:9.8")
    compileOnly("org.ow2.asm:asm-commons:9.8")
}

configurations {
    testCompileOnly {
        extendsFrom(compileOnly.get())
    }
    testRuntimeOnly {
        extendsFrom(compileOnly.get())
    }
}

val astdModulePaths = listOf(
    ":astd-api", ":astd-api-render",
    ":astd-impl", ":astd-ui", ":astd-render", ":astd-combat", ":astd-campaign", ":astd-automation",
)

/** 与 SDG 的 GAME_DIR 依赖解析保持一致，兼容 mod_info.json 的 `#` 注释和尾逗号。 */
val readAstdGameModInfo: (File) -> Map<*, *>? = { infoFile ->
    val cleaned = infoFile.readText()
        .replace(Regex("(?m)#.*$"), "")
        .replace(Regex(",(\\s*[}\\]])"), "$1")
    try {
        groovy.json.JsonSlurper().setType(groovy.json.JsonParserType.LAX).parseText(cleaned) as Map<*, *>
    } catch (e: Exception) {
        logger.warn("ASTD: 解析 ${infoFile.absolutePath} 失败，跳过该模组依赖桥：${e.message}")
        null
    }
}

val astdGameCompileOnlyJars: List<File> by lazy {
    val gameDir = file(providers.gradleProperty("starsector.gameDir").get())
    val jars = mutableListOf<File>()
    jars += fileTree(gameDir) { include("*.jar") }.files
    jars += fileTree(gameDir.resolve("starfarer-core")) { include("*.jar") }.files
    val modDirs = gameDir.resolve("mods").listFiles { f -> f.isDirectory } ?: emptyArray()
    for (modDir in modDirs) {
        val infoFile = modDir.resolve("mod_info.json")
        if (!infoFile.isFile) continue
        val parsed = readAstdGameModInfo(infoFile) ?: continue
        if ((parsed["id"] as? String) == "asteria_directorate") continue
        val modJars = (parsed["jars"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        jars += modJars.map { modDir.resolve(it) }.filter { it.isFile }
    }
    jars.distinct()
}

val astdProductionModIds = listOf(
    "lw_lazylib", "MagicLib", "shaderLib", "BoxUtil", "IndEvo", "lunalib", "second_in_command", "shipmasterysystem",
)

val astdProductionRuntimeJars: List<File> by lazy {
    val gameDir = file(providers.gradleProperty("starsector.gameDir").get())
    val jars = mutableListOf<File>()
    jars += fileTree(gameDir) { include("*.jar") }.files
    jars += fileTree(gameDir.resolve("starfarer-core")) { include("*.jar") }.files
    val missingModIds = astdProductionModIds.toMutableSet()
    val modDirs = gameDir.resolve("mods").listFiles { f -> f.isDirectory } ?: emptyArray()
    for (modDir in modDirs) {
        val infoFile = modDir.resolve("mod_info.json")
        if (!infoFile.isFile) continue
        val parsed = readAstdGameModInfo(infoFile) ?: continue
        val modId = parsed["id"] as? String ?: continue
        if (!missingModIds.remove(modId)) continue
        val modJars = (parsed["jars"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        val resolved = modJars.map { modDir.resolve(it) }
        val missingJar = resolved.firstOrNull { !it.isFile }
        if (missingJar != null) throw GradleException("生产依赖检查缺少模组 JAR：${missingJar.absolutePath}")
        jars += resolved
    }
    if (missingModIds.isNotEmpty()) {
        throw GradleException("生产依赖检查缺少模组：${missingModIds.sorted().joinToString()}")
    }
    jars.distinct()
}

astdModulePaths.forEach { path ->
    project(path) {
        repositories {
            maven {
                url = uri("https://maven.aliyun.com/repository/public")
            }
            mavenCentral()
        }
        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<JavaPluginExtension> {
                toolchain.languageVersion.set(JavaLanguageVersion.of(17))
            }
            tasks.withType<KotlinCompile>().configureEach {
                compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
            tasks.withType<Test>().configureEach {
                workingDir = rootProject.projectDir
            }
            configurations.named("testCompileOnly") { extendsFrom(configurations.compileOnly.get()) }
            configurations.named("testRuntimeOnly") { extendsFrom(configurations.compileOnly.get()) }
            dependencies.add("compileOnly", files(astdGameCompileOnlyJars))
            dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-test")
            dependencies.add("testImplementation", "org.mockito:mockito-core:5.5.0")
            plugins.withId("java-test-fixtures") {
                configurations.named("testFixturesCompileOnly") { extendsFrom(configurations.compileOnly.get()) }
                configurations.named("testFixturesRuntimeOnly") { extendsFrom(configurations.compileOnly.get()) }
                dependencies.add("testFixturesImplementation", "org.jetbrains.kotlin:kotlin-test")
                dependencies.add("testFixturesImplementation", "org.mockito:mockito-core:5.5.0")
            }
        }
    }
}

/** 自动化测试模块是否进入打包（dev/deploy 默认包含；release 模式默认排除，可用 -Pastd.includeAutomation 显式覆盖）。 */
val astdIncludeAutomation: Boolean =
    providers.gradleProperty("astd.includeAutomation").map(String::toBooleanStrict).orElse(!astdRelease).get()

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // dev 验收 agent 挂接属性只随 dev 包携带；release 包不得声明 Premain-Class。
    if (!astdRelease) {
        manifest {
            attributes(
                "Premain-Class" to "cn.kasuminova.astd.agent.AsteriaDevStorageAcceptanceAgent",
                "Can-Retransform-Classes" to "true",
                "Can-Redefine-Classes" to "true",
            )
        }
    }
}

// release 模式：dev 验收 agent 类不进主 jar（agent 仅经 -javaagent + Premain-Class 激活，release 无此入口；
// 代码与数据侧均无对 cn.kasuminova.astd.agent 的引用）。
tasks.named<Jar>("jar") {
    if (astdRelease) {
        exclude("cn/kasuminova/astd/agent/**")
    }
}

// 验收 agent jar 仅 dev 构建注册；release 模式下 SDG copyJars 的附加产物汇集自然不含它。
val acceptanceAgentJar = if (!astdRelease) tasks.register<Jar>("acceptanceAgentJar") {
    archiveClassifier.set("acceptance-agent")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceSets.main.get().output) {
        include("cn/kasuminova/astd/agent/**")
    }
    from({
        configurations.compileClasspath.get()
            .filter { file -> file.extension == "jar" }
            .map { file -> zipTree(file) }
    }) {
        include("org/objectweb/asm/**")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Premain-Class" to "cn.kasuminova.astd.agent.AsteriaDevStorageAcceptanceAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
} else null

// build 时自动生成 ss-csv 到 build/generated/ss-csv/
tasks.named("build") {
    dependsOn(":astd-csv:generateSsCsv")
    if (acceptanceAgentJar != null) dependsOn(acceptanceAgentJar)
}

// release 模式：sources / 验收 agent jar 不进产物布局。
// copyJars 是 Sync 任务，排除的同时会清掉布局 jars/ 内的历史副本，mod_info.json 随之只剩主 jar。
tasks.named<Sync>("copyJars") {
    if (astdRelease) {
        exclude("**/*-sources.jar")
        exclude("**/*-acceptance-agent.jar")
    }
}

// 生产目录使用 build/generated/ss-csv 叠加静态 contents，保持 contents 不被自动覆盖。
// automation 资源（测试战役等）由 astd-automation 模块的 contents 提供，release 打包时排除。
tasks.named<Sync>("copyContents") {
    dependsOn(":astd-csv:generateSsCsv")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    // ss-csv 直写 contents 的本地产物清单（.ss-csv-manifest），不属于 mod 内容。
    exclude(".ss-csv-manifest")
    from(layout.buildDirectory.dir("generated/ss-csv"))
    if (astdIncludeAutomation) {
        from("modules/internal/astd-automation/contents")
    }
}

val verifyProductionDependencies = tasks.register<Exec>("verifyProductionDependencies") {
    group = "verification"
    description = "使用 jdeps 检查生产 JAR 是否引用原版及声明支持模组之外的类。"
    dependsOn("jar")
    doFirst {
        if (!astdRelease) throw GradleException("生产依赖检查必须使用 -Pastd.release=true")
        commandLine(
            "python3",
            rootProject.file("tools/check_production_dependencies.py").absolutePath,
            "--jar",
            tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath,
            "--class-path",
            astdProductionRuntimeJars.joinToString(File.pathSeparator) { it.absolutePath },
            "--report",
            layout.buildDirectory.file("reports/production-dependencies/jdeps.txt").get().asFile.absolutePath,
        )
    }
}

tasks.named("check") {
    if (astdRelease) dependsOn(verifyProductionDependencies)
}

tasks.named<Zip>("zipModProduction") {
    includeEmptyDirs = false
    eachFile { path = "Asteria Directorate/$path" }
    if (astdRelease) dependsOn(verifyProductionDependencies)
}

val starsectorGameDir: String = providers.gradleProperty("starsector.gameDir")
    .orElse("/mnt/store/Games/Starsector098-linux")
    .get()

tasks.register<Exec>("smokeTestLauncher") {
    group = "verification"
    description = "部署模组并启动 Starsector/SSOptimizer 注入路径做启动烟测。"
    dependsOn("deployMod")
    commandLine("bash", "tools/smoke_test_game_launch.sh", starsectorGameDir, "30", "launcher")
}

// automation 场景可由 ASTD_AUTOMATION_SCENARIO 环境变量覆盖，默认 arc_production_ships_vfx_tooltip。
val smokeTestScenario: String =
    (System.getenv("ASTD_AUTOMATION_SCENARIO")?.takeIf { it.isNotBlank() })
        ?: "arc_production_ships_vfx_tooltip"

tasks.register<Exec>("launchSmokeTestGame") {
    group = "verification"
    description = "部署模组并通过 SSOptimizer automation 路径启动实机场景（默认 ARC production，可由 ASTD_AUTOMATION_SCENARIO 覆盖）。"
    dependsOn("deployMod")
    environment("ASTD_AUTOMATION_SCENARIO", smokeTestScenario)
    commandLine("bash", "tools/smoke_test_game_launch.sh", starsectorGameDir, "120", "automation")
}

tasks.register<Exec>("verifySmokeTestGameEvidence") {
    group = "verification"
    description = "验证 SSOptimizer automation 输出的 ARC production 实机证据。"
    dependsOn("launchSmokeTestGame")
    commandLine(
        "python3",
        "tools/verify_ingame_vfx_automation.py",
        "$starsectorGameDir/ssoptimizer-automation-output/astd-ingame-automation-telemetry.json",
        "--log",
        "$starsectorGameDir/starsector.log",
    )
}

tasks.register("smokeTestGame") {
    group = "verification"
    description = "部署模组、启动 ARC production 实机场景，并校验 SSOptimizer automation 证据。"
    dependsOn("verifySmokeTestGameEvidence")
}

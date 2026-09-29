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

/**
 * release 打包统一开关：`-Pastd.release=true` 产出玩家向干净包。
 * 开启后：automation 内容与类不进包、jars 只剩主 jar（无 sources / 验收 agent jar）、
 * 主 jar manifest 不带 Premain-Class、版本默认 0.1.0。
 */
val astdRelease: Boolean =
    providers.gradleProperty("astd.release").map(String::toBooleanStrict).orElse(false).get()

/** 模组版本：`-Pastd.modVersion` 显式覆盖；release 默认 0.1.0，dev 默认 1.0-SNAPSHOT。flows 到 mod_info / jar 名 / zip 名。 */
val astdModVersion: String =
    providers.gradleProperty("astd.modVersion").orElse(if (astdRelease) "0.1.0" else "1.0-SNAPSHOT").get()

version = astdModVersion

starsector {
    modId.set("asteria_directorate")
    deployDirName.set("ASTD")
    modName.set("Asteria Directorate")
    author.set("Hikari_Nova")
    description.set("Description")
    gameVersion.set("0.98a")
    modPlugin.set("cn.kasuminova.astd.AsteriaDirectoratePlugin")
    dependency("lw_lazylib", "LazyLib")
    dependency("MagicLib", "MagicLib")
    dependency("shaderLib", "GraphicsLib")
    dependency("BoxUtil", "zz BoxUtil")
    // LunaLib 为可选前置：不声明进 mod_info 依赖（未装也能加载），
    // 运行时经 LunaLibSupport.isAvailable()（modManager.isModEnabled）检测；
    // 编译期经各模块的已装模组 jar 桥（astdGameCompileOnlyJars，扫描 mods/ 全部 mod_info）挂 compileOnly。
    gameDependencyMode.set(GameDependencyMode.GAME_DIR)
    gameDir.fileValue(file(providers.gradleProperty("starsector.gameDir").get()))
    launchMode.set(LaunchMode.NANOFORGE)
    decompilerVersion.set(providers.gradleProperty("decompiler.version").orElse("1.9.3"))
}

repositories {
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
}

dependencies {
    // 根装配工程：mod 插件入口类引用 campaign/combat/impl 的装配点。
    implementation(project(":astd-campaign"))
    implementation(project(":astd-combat"))
    implementation(project(":astd-impl"))

    testImplementation(kotlin("test"))
    // 仓库纪律/数据校验测试共用的 CSV 读取工具（astd-csv testFixtures）。
    testImplementation(testFixtures(project(":astd-csv")))
    // 战斗 API（ShipAPI/WeaponAPI/CombatEngineAPI 均为 jar 接口）单测桩：禁止反射手搓代理，统一走 mockito。
    testImplementation("org.mockito:mockito-core:5.5.0")
    // SDG GAME_DIR 模式自动把游戏 jar 与已装模组 jar 挂到 compileOnly，无需单独声明模组依赖。
    // agent 字节码改写用 ASM：游戏与已装模组的 mod_info.json 均不导出 ASM，显式声明（版本与 NanoForge 运行时对齐）。
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

// ---------------------------------------------------------------------------
// 多模块约定：SDG GAME_DIR 只给应用插件的根工程接线，以下为各 astd 模块装配同等
// compileOnly（游戏根目录 jar + starfarer-core jar + 已装模组 mod_info.json 的 jars 桥），
// 并统一 toolchain / 测试依赖 / test 配置继承。模块自身只声明 plugins 与内部 project 依赖。
// ---------------------------------------------------------------------------

/** 参与游戏代码编译的 astd 模块（astd-csv 是纯生成工具，不在此列）。 */
val astdModulePaths = listOf(
    ":astd-api", ":astd-api-render",
    ":astd-impl", ":astd-ui", ":astd-render", ":astd-combat", ":astd-campaign", ":astd-automation",
)

/**
 * 与 SDG GAME_DIR + 第三方 mod 依赖桥等价的 compileOnly jar 清单
 * （逻辑对齐 SdgModPlugin.wireGameDirDeps / ModJarIndexImpl，含 `#` 注释与尾逗号宽松解析）。
 */
val astdGameCompileOnlyJars: List<File> by lazy {
    val gameDir = file(providers.gradleProperty("starsector.gameDir").get())
    val jars = mutableListOf<File>()
    jars += fileTree(gameDir) { include("*.jar") }.files
    jars += fileTree(gameDir.resolve("starfarer-core")) { include("*.jar") }.files
    val modDirs = gameDir.resolve("mods").listFiles { f -> f.isDirectory } ?: emptyArray()
    for (modDir in modDirs) {
        val infoFile = modDir.resolve("mod_info.json")
        if (!infoFile.isFile) continue
        val cleaned = infoFile.readText()
            .replace(Regex("(?m)#.*$"), "")
            .replace(Regex(",(\\s*[}\\]])"), "$1")
        val parsed = try {
            groovy.json.JsonSlurper().setType(groovy.json.JsonParserType.LAX).parseText(cleaned) as Map<*, *>
        } catch (e: Exception) {
            logger.warn("ASTD: 解析 ${infoFile.absolutePath} 失败，跳过该模组依赖桥：${e.message}")
            continue
        }
        if ((parsed["id"] as? String) == "asteria_directorate") continue
        val modJars = (parsed["jars"] as? List<*>)?.filterIsInstance<String>().orEmpty()
        jars += modJars.map { modDir.resolve(it) }.filter { it.isFile }
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
            // 模块测试默认工作目录是模块目录；仓库级测试用相对路径读 contents/，统一钉到根目录。
            tasks.withType<Test>().configureEach {
                workingDir = rootProject.projectDir
            }
            configurations.named("testCompileOnly") { extendsFrom(configurations.compileOnly.get()) }
            configurations.named("testRuntimeOnly") { extendsFrom(configurations.compileOnly.get()) }
            dependencies.add("compileOnly", files(astdGameCompileOnlyJars))
            dependencies.add("testImplementation", "org.jetbrains.kotlin:kotlin-test")
            dependencies.add("testImplementation", "org.mockito:mockito-core:5.5.0")
            // testFixtures 源集与 test 同等接线（游戏 jar 在 compileOnly，mock/log4j 桩需要）。
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

val starsectorGameDir: String = providers.gradleProperty("starsector.gameDir")
    .orElse("/mnt/store/Games/Starsector098-linux")
    .get()

tasks.register<Exec>("smokeTestLauncher") {
    group = "verification"
    description = "部署模组并启动 Starsector/SSOptimizer 注入路径做启动烟测。"
    dependsOn("deployMod")
    commandLine("bash", "tools/smoke_test_game_launch.sh", starsectorGameDir, "30", "launcher")
}

// automation 场景可由外部 ASTD_AUTOMATION_SCENARIO 环境变量覆盖（默认 ARC production，
// 保持既有行为）；阶段一决明场景通过 ASTD_AUTOMATION_SCENARIO=lens_phase1_foundation 启动。
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

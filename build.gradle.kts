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
    id("io.github.nanoforged.sdg.mod") version "0.1.0-SNAPSHOT"
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

sdg {
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
    dependency("lunalib", "LunaLib")
    gameDependencyMode.set(GameDependencyMode.GAME_DIR)
    gameDir.fileValue(file(providers.gradleProperty("starsector.gameDir").get()))
    launchMode.set(LaunchMode.VANILLA)
    decompilerVersion.set(providers.gradleProperty("decompiler.version").orElse("1.9.3"))
}

repositories {
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
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
    manifest {
        attributes(
            "Premain-Class" to "cn.kasuminova.astd.agent.AsteriaDevStorageAcceptanceAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}

val acceptanceAgentJar = tasks.register<Jar>("acceptanceAgentJar") {
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
}

/**
 * 生成空心圆环贴图到 contents（供 SpriteEntity 光圈使用）。
 *
 * 背景：运行时尝试写入 mods 目录在部分环境下会失败（权限/路径/只读），导致贴图缺失从而完全不渲染。
 * 因此改为在构建阶段确保 `contents/graphics/fx/smd_generated_ring.png` 存在。
 */
val generateRingTextureToContents = tasks.register("generateRingTextureToContents") {
    group = "starsector mod"
    description = "生成 SpriteEntity 光圈用的空心圆环贴图到 contents/graphics/fx/"

    val outFile = project.layout.projectDirectory.file("contents/graphics/fx/smd_generated_ring.png").asFile
    outputs.file(outFile)

    doLast {
        val force = (project.findProperty("ringTextureForce")?.toString()?.toBooleanStrictOrNull() ?: false)
        if (outFile.exists() && !force) return@doLast

        outFile.parentFile?.mkdirs()

        val size = 256
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

        val cx = (size - 1) * 0.5f
        val cy = (size - 1) * 0.5f
        val rMax = min(cx, cy)

        // 视觉目标：明显“空心环”，边缘略微柔和，外侧带少量 glow 便于 bloom。
        val rOuter = 0.48f
        val rInner = 0.44f
        val edge = 0.010f
        val glowSigma = 0.020f
        val glowStrength = 0.22f

        fun smoothstep(a: Float, b: Float, x: Float): Float {
            if (a == b) return if (x < a) 0f else 1f
            var t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            t = t * t * (3f - 2f * t)
            return t
        }

        for (y in 0 until size) {
            val dy = (y - cy) / rMax
            for (x in 0 until size) {
                val dx = (x - cx) / rMax
                val r = sqrt(dx * dx + dy * dy)

                val ring = smoothstep(rInner, rInner + edge, r) * (1f - smoothstep(rOuter - edge, rOuter, r))
                val glow = exp(-((r - rOuter) * (r - rOuter)) / (glowSigma * glowSigma)) * glowStrength

                val a = ((ring + glow).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val argb = (a shl 24) or 0xFFFFFF
                img.setRGB(x, y, argb)
            }
        }

        ImageIO.write(img, "png", outFile)
    }
}

// 确保 production/部署前一定生成贴图（会写入 contents，符合“提前生成资源”的需求）。
tasks.named("copyContents") {
    dependsOn(generateRingTextureToContents)
}

// build 时自动生成 ss-csv 到 build/generated/ss-csv/
tasks.named("build") {
    dependsOn(":ss-csv:generateSsCsv")
    dependsOn(acceptanceAgentJar)
}

// 生产目录使用 build/generated/ss-csv 叠加静态 contents，保持 contents 不被自动覆盖。
tasks.named<Sync>("copyContents") {
    dependsOn(":ss-csv:generateSsCsv")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(layout.buildDirectory.dir("generated/ss-csv"))
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
// 保持既有行为）；阶段一引力透镜场景通过 ASTD_AUTOMATION_SCENARIO=lens_phase1_foundation 启动。
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

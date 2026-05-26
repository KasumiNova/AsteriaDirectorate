import cn.kasuminova.starsector.gradle.starsector
import cn.kasuminova.starsector.gradle.DecompileSourcesTask
import org.gradle.api.file.DuplicatesStrategy
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

plugins {
    kotlin("jvm")
    id("starsector-mod-plugin")
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

repositories {
    maven {
        url = uri("https://maven.aliyun.com/repository/public")
    }
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // starsector 默认包含所有编译器和运行时模组依赖，不需要单独声明其他模组依赖。
    starsector(project)
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

tasks.withType<DecompileSourcesTask>().configureEach {
    decompilerVersion.set(project.findProperty("decompiler.version")?.toString() ?: "1.9.3")
    maxParallelDecompile.set(project.findProperty("decompiler.maxParallelThreads")?.toString()?.toIntOrNull() ?: 2)
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
    .orElse("/mnt/windows_data/Games/Starsector098-linux")
    .get()

tasks.register<Exec>("smokeTestLauncher") {
    group = "verification"
    description = "部署模组并启动 Starsector/SSOptimizer 注入路径做启动烟测。"
    dependsOn("deployMod")
    commandLine("bash", "tools/smoke_test_game_launch.sh", starsectorGameDir, "30", "launcher")
}

tasks.register<Exec>("smokeTestGame") {
    group = "verification"
    description = "部署模组并通过 SSOptimizer automation 路径进入 ARC production 实机验收场景。"
    dependsOn("deployMod")
    environment("ASTD_AUTOMATION_SCENARIO", "arc_production_ships_vfx_tooltip")
    commandLine("bash", "tools/smoke_test_game_launch.sh", starsectorGameDir, "120", "automation")
}

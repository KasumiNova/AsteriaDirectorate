package cn.kasuminova.starsector.gradle

import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.file.Directory
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.extra
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mod 部署和调试相关任务
 * 负责将 Mod 部署到游戏目录并启动调试
 */
class ModDeploymentTasks(
    private val project: Project,
    private val metadata: ModMetadata,
    private val modProductionDir: Provider<Directory>
) {

    private data class JavaRuntimeInfo(
        val executable: File,
        val majorVersion: Int?,
        val versionLine: String,
        val isJetBrainsRuntime: Boolean,
    )

    fun register() {
        registerCleanDeployTask()
        registerDeployTask()
        registerLaunchGameTask()
    }

    private fun probeJavaRuntime(executable: File): JavaRuntimeInfo? {
        if (!executable.exists() || !executable.isFile) return null

        return try {
            val process = ProcessBuilder(executable.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(5, TimeUnit.SECONDS)

            val firstLine = output.lineSequence().firstOrNull().orEmpty()
            val combined = output.lowercase()
            val major = Regex("version \"(\\d+)").find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
            JavaRuntimeInfo(
                executable = executable,
                majorVersion = major,
                versionLine = firstLine,
                isJetBrainsRuntime = combined.contains("jetbrains") || combined.contains(" jbr") || combined.contains("jbr-"),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 清理游戏目录中的旧 Mod 文件
     */
    private fun registerCleanDeployTask() {
        // Delete 任务在某些平台/文件系统（例如 Windows/NTFS 挂载）上会因为“无法删除子目录/文件”而直接失败。
        // 这会阻断 deployMod/launchGame。
        // 这里改为“尽力删除，失败仅警告”，把硬失败留给真正需要的场景（例如文件覆盖失败）。
        project.tasks.register("cleanDeploy") {
            group = "starsector mod"
            description = "清理游戏目录中的旧 Mod 文件"

            doLast {
                val gameDirPath = metadata.gameDir.get()
                if (gameDirPath.isEmpty()) {
                    throw IllegalStateException("未配置 starsector.gameDir，请在 gradle.properties 中配置游戏根目录")
                }

                val gameDir = project.file(gameDirPath)
                if (!gameDir.exists()) {
                    throw IllegalStateException("游戏目录不存在: ${gameDir.absolutePath}")
                }

                val modId = metadata.modId.get()
                val targetModDir = File(gameDir, "mods/$modId")

                if (targetModDir.exists()) {
                    project.logger.lifecycle("正在清理旧的 Mod 文件: ${targetModDir.absolutePath}")
                    val ok = try {
                        targetModDir.deleteRecursively()
                    } catch (t: Throwable) {
                        project.logger.warn(
                            "无法删除旧 Mod 目录（将继续执行 deployMod 覆盖式部署）: ${targetModDir.absolutePath}",
                            t
                        )
                        false
                    }

                    if (!ok && targetModDir.exists()) {
                        project.logger.warn(
                            "旧 Mod 目录仍然存在：${targetModDir.absolutePath}。" +
                                " 这通常是因为游戏正在运行并占用 jar，或有进程将工作目录设在该目录下。" +
                                " 你可以先关闭游戏/相关进程后再运行 cleanDeploy。"
                        )
                    }
                } else {
                    project.logger.lifecycle("目标 Mod 目录不存在，跳过清理: ${targetModDir.absolutePath}")
                }
            }
        }
    }

    /**
     * 部署 Mod 到游戏目录
     */
    private fun registerDeployTask() {
        // 使用 Copy 进行覆盖式部署：
        // - 不强制删除目标目录（减少 Windows/NTFS 挂载/文件占用导致的失败）
        // - 仍然会覆盖同名文件（jar/数据/贴图等）
        project.tasks.register<Copy>("deployMod") {
            dependsOn("modProduction", "cleanDeploy")
            group = "starsector mod"
            description = "部署 Mod 到游戏目录"

            from(modProductionDir)

            into(project.provider {
                val gameDirPath = metadata.gameDir.get()
                if (gameDirPath.isEmpty()) {
                    throw IllegalStateException("未配置 starsector.gameDir，请在 gradle.properties 中配置游戏根目录")
                }

                val gameDir = project.file(gameDirPath)
                if (!gameDir.exists()) {
                    throw IllegalStateException("游戏目录不存在: ${gameDir.absolutePath}")
                }

                val modId = metadata.modId.get()
                File(gameDir, "mods/$modId")
            })

            doLast {
                project.logger.lifecycle("Mod 部署完成: ${metadata.modName.get()}")
            }
        }
    }

    /**
     * 启动游戏
     * 通过 -Pdebug=true 启用调试模式
     */
    private fun registerLaunchGameTask() {
        val writeSsCsvPath = ":ss-csv:writeSsCsvToContents"
        val deployMod = project.tasks.named("deployMod")
        val modProduction = project.tasks.named("modProduction")

        // 当执行 launchGame 时，自动允许 ss-csv 写回 contents（等同 -PssCsvForce=true）
        val launchRequested = project.gradle.startParameter.taskNames.any {
            it == "launchGame" || it.endsWith(":launchGame")
        }
        if (launchRequested) {
            project.rootProject.allprojects.forEach { p ->
                p.extra["ssCsvForce"] = "true"
            }
        }

        // 确保 ss-csv 写回先于 modProduction/deployMod 执行
        deployMod.configure {
            mustRunAfter(writeSsCsvPath)
        }
        modProduction.configure {
            mustRunAfter(writeSsCsvPath)
        }

        project.tasks.register<JavaExec>("launchGame") {
            dependsOn(writeSsCsvPath, deployMod)
            group = "starsector mod"
            description = "启动 Starsector."

            val gameDirPath = metadata.gameDir.get()
            if (gameDirPath.isEmpty()) {
                throw IllegalStateException("未配置 starsector.gameDir，请在 gradle.properties 中配置游戏根目录")
            }

            val gameDir = project.file(gameDirPath)
            if (!gameDir.exists()) {
                throw IllegalStateException("游戏目录不存在: ${gameDir.absolutePath}")
            }

            workingDir = gameDir

            // 从 launch-config.json 读取配置
            val configFile = project.file("launch-config.json")
            if (!configFile.exists()) {
                throw IllegalStateException("配置文件 launch-config.json 不存在")
            }
            @Suppress("UNCHECKED_CAST")
            val config = JsonSlurper().parse(configFile) as Map<String, Any>

            // 根据操作系统选择 JVM 参数
            val osName = System.getProperty("os.name").lowercase()
            val osJvmArgsKey = when {
                osName.contains("win") -> "windows"
                osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> "linux"
                osName.contains("mac") -> "mac"
                else -> "linux" // 默认为 linux
            }

            val javaExt = if (osName.contains("win")) ".exe" else ""
            val userHome = File(System.getProperty("user.home"))
            val configuredJava = listOfNotNull(
                project.findProperty("starsector.javaExec")?.toString(),
                System.getenv("STARSECTOR_JAVA_EXEC"),
            ).map { File(it) }
            val configuredJavaHomes = listOfNotNull(
                project.findProperty("starsector.javaHome")?.toString(),
                System.getenv("STARSECTOR_JAVA_HOME"),
                System.getenv("JBR17_HOME"),
            ).map { File(it, "bin/java$javaExt") }

            val jbr17Candidates = buildList {
                val jdksDir = File(userHome, ".jdks")
                val jbrHomes = jdksDir.listFiles()
                    ?.filter { it.isDirectory && it.name.contains("jbr-17") }
                    ?.sortedByDescending { it.name }
                    .orEmpty()
                addAll(jbrHomes.map { File(it, "bin/java$javaExt") })
                add(File(userHome, ".jdks/jbr-17.0.14/bin/java$javaExt"))
            }

            val bundledJavaCandidates = when (osJvmArgsKey) {
                "windows" -> listOf(
                    File(gameDir, "zulu25_win/bin/java$javaExt"),
                    File(gameDir, "jre/bin/java$javaExt"),
                )
                "mac" -> listOf(
                    File(gameDir, "zulu25_mac/bin/java$javaExt"),
                    File(gameDir, "jre_mac/Contents/Home/bin/java$javaExt"),
                )
                else -> listOf(
                    File(gameDir, "zulu25_linux/bin/java$javaExt"),
                    File(gameDir, "jre_linux/bin/java$javaExt"),
                )
            }
            val fallbackJava = File(System.getProperty("java.home"), "bin/java$javaExt")
            val javaCandidates = configuredJava + configuredJavaHomes + jbr17Candidates + bundledJavaCandidates + listOf(fallbackJava)
            val selectedRuntime = javaCandidates
                .asSequence()
                .mapNotNull(::probeJavaRuntime)
                .firstOrNull()
                ?: throw IllegalStateException("未找到可用的 Java 运行时用于 launchGame")
            executable(selectedRuntime.executable.absolutePath)
            project.logger.lifecycle("launchGame 使用 Java: ${selectedRuntime.executable.absolutePath} (${selectedRuntime.versionLine})")

            @Suppress("UNCHECKED_CAST")
            val jvmArgsConfig = config["jvmArgs"] as Map<String, List<String>>
            val commonArgs = jvmArgsConfig["common"] ?: emptyList()
            val osArgs = jvmArgsConfig[osJvmArgsKey] ?: emptyList()
            val filteredCommonArgs = commonArgs.filterNot { arg ->
                when {
                    arg == "-XX:+UseCompactObjectHeaders" && (selectedRuntime.majorVersion ?: Int.MAX_VALUE) < 24 -> true
                    arg == "-XX:+AllowEnhancedClassRedefinition" && !selectedRuntime.isJetBrainsRuntime -> true
                    else -> false
                }
            }
            val removedArgs = commonArgs - filteredCommonArgs.toSet()
            if (removedArgs.isNotEmpty()) {
                project.logger.lifecycle("launchGame 已移除与当前 JVM 不兼容的参数: ${removedArgs.joinToString(", ")}")
            }
            jvmArgs = filteredCommonArgs + osArgs

            // 设置 classpath
            @Suppress("UNCHECKED_CAST")
            val classpathJars = config["classpath"] as List<String>
            classpath = project.files(classpathJars.map { File(gameDir, it) })

            // 设置主类
            mainClass.set("com.fs.starfarer.StarfarerLauncher")
        }
    }
}

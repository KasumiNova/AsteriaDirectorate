package cn.kasuminova.starsector.gradle

import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.json.JSONObject
import java.io.File

fun DependencyHandler.starsector(project: Project) {
    // 避免把“已部署到 gameDir/mods/<mod.id>”的本模组 jar 扫进 compileOnly。
    // 否则当用户同时运行 `build deployMod` 时，cleanDeploy 可能先删除该 jar，
    // compileKotlin 仍持有旧 classpath，最终导致 Kotlin 编译器尝试读取一个不存在的 jar 并崩溃。
    val currentModId: String = project.providers.gradleProperty("mod.id")
        .orElse("")
        .get()

    val gameDirProvider = project.providers.gradleProperty("starsector.gameDir")
    if (!gameDirProvider.isPresent) {
        project.logger.warn("starsector.gameDir is not set in gradle.properties. Skipping game and mod dependencies.")
        return
    }
    val gameDir = File(gameDirProvider.get())

    // 用于做“已声明依赖”缺失提示（不影响实际依赖添加逻辑）
    val requiredModIds: Set<String> = project.providers.gradleProperty("mod.dependencies")
        .orNull
        ?.split(',')
        ?.mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            trimmed.split(':', limit = 2)
                .getOrNull(0)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        ?.toSet()
        ?: emptySet()
    val discoveredModIds = mutableSetOf<String>()

    // 添加游戏核心库和 API
    val gameJars = project.fileTree(mapOf("dir" to gameDir, "include" to listOf("*.jar")))
    val coreJars = project.fileTree(mapOf("dir" to gameDir.resolve("starfarer-core"), "include" to listOf("*.jar")))
    add("compileOnly", gameJars)
    add("compileOnly", coreJars)

    // 动态添加 Mods 的 jar 依赖
    val modsDir = gameDir.resolve("mods")
    if (modsDir.exists() && modsDir.isDirectory) {
        modsDir.listFiles()?.forEach { modDir ->
            if (modDir.isDirectory) {
                val modInfoFile = modDir.resolve("mod_info.json")
                if (modInfoFile.exists()) {
                    try {
                        // 读取文件内容并移除注释
                        val content = modInfoFile.readText(Charsets.UTF_8)
                            .replace(Regex("(?m)#.*$"), "") // 移除 # 注释
                            .replace(Regex(",(\\s*[]}])"), "$1") // 移除末尾的逗号

                        val modInfo = JSONObject(content)
                        val modId = modInfo.optString("id")
                        discoveredModIds += modId

                        // 跳过本模组：避免将已部署 jar 作为 compileOnly 依赖引入（见函数顶部注释）。
                        if (currentModId.isNotEmpty() && modId == currentModId) {
                            return@forEach
                        }
                        if (modInfo.has("jars")) {
                            val jars = modInfo.getJSONArray("jars")
                            for (i in 0 until jars.length()) {
                                val jarName = jars.getString(i)
                                val jarFile = modDir.resolve(jarName)
                                if (jarFile.exists()) {
                                    add("compileOnly", project.files(jarFile.absolutePath))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        project.logger.warn("Failed to parse mod_info.json for mod: ${modDir.name}. Reason: ${e.message}")
                    }
                }
            }
        }
    }

    if (requiredModIds.isNotEmpty()) {
        val missing = requiredModIds - discoveredModIds
        if (missing.isNotEmpty()) {
            project.logger.warn(
                "The following mod.dependencies were not found under starsector.gameDir/mods (compileOnly jars may be missing): ${missing.joinToString(", ")}."
            )
        }
    }
}

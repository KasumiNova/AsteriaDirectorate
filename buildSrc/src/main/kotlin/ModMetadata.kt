package cn.kasuminova.starsector.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Mod 元数据配置
 * 从 gradle.properties 读取所有 Mod 相关配置
 */
class ModMetadata(project: Project) {
    val modId: Provider<String> = project.providers.gradleProperty("mod.id")
        .orElse("asteria_directorate")

    val modName: Provider<String> = project.providers.gradleProperty("mod.name")
        .orElse("Starsector Mod Demo")

    val modAuthor: Provider<String> = project.providers.gradleProperty("mod.author")
        .orElse("Author")

    val modDescription: Provider<String> = project.providers.gradleProperty("mod.description")
        .orElse("Description")

    val modGameVersion: Provider<String> = project.providers.gradleProperty("mod.gameVersion")
        .orElse("0.97a-RC11")

    val modPlugin: Provider<String> = project.providers.gradleProperty("mod.plugin")
        .orElse("")

    val modDependencies: String? = project.providers.gradleProperty("mod.dependencies")
        .orNull

    // 游戏文件夹配置
    val gameDir: Provider<String> = project.providers.gradleProperty("starsector.gameDir")
        .orElse("")

}

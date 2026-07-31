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

    /**
     * 部署目录名（游戏 mods/ 下的文件夹名），与 mod.id 解耦。
     *
     * mod.id 是游戏内的模组标识，会写入 mod_info.json 并影响存档/依赖兼容，因此保持不变。
     * 部署目录名仅用于落盘路径，固定为 ASTD，避免与源码工程目录 Asteria_Directorate
     * 在大小写不敏感的文件系统（如 NTFS）上发生路径冲突。
     */
    val deployDirName: Provider<String> = project.providers.gradleProperty("mod.deployDirName")
        .orElse("ASTD")

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

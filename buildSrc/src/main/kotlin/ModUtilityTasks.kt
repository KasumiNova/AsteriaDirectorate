package cn.kasuminova.starsector.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.file.Directory
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.register

/**
 * Mod 工具类任务
 * 包含辅助功能如打印信息、部署等
 */
class ModUtilityTasks(
    private val project: Project,
    private val metadata: ModMetadata,
    private val modProductionDir: Provider<Directory>
) {

    fun register() {
        registerPrintInfoTask()
        registerDeployTask()
    }

    private fun registerPrintInfoTask() {
        project.tasks.register("printModInfo") {
            group = "starsector mod"
            description = "打印当前 Mod 配置信息"

            doLast {
                println("=".repeat(50))
                println("Mod 配置信息:")
                println("  ID: ${metadata.modId.get()}")
                println("  名称: ${metadata.modName.get()}")
                println("  作者: ${metadata.modAuthor.get()}")
                println("  版本: ${project.version}")
                println("  游戏版本: ${metadata.modGameVersion.get()}")
                println("  插件类: ${metadata.modPlugin.get()}")
                println("  依赖: ${metadata.modDependencies ?: "无"}")
                println("=".repeat(50))
            }
        }
    }

    private fun registerDeployTask() {
        project.tasks.register<Copy>("deployToStarsector") {
            group = "starsector mod"
            description = "部署 Mod 到 Starsector mods 目录（需配置 starsector.modsDir）"

            dependsOn("modProduction")

            val modsDir = project.providers.gradleProperty("starsector.modsDir").orNull
            if (modsDir != null) {
                from(modProductionDir)
                into(project.file(modsDir).resolve(metadata.deployDirName.get()))
            }

            doFirst {
                if (modsDir == null) {
                    throw GradleException("请在 gradle.properties 中设置 starsector.modsDir 属性")
                }
            }
        }
    }
}
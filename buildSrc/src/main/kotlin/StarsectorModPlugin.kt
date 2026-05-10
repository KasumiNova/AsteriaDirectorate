package cn.kasuminova.starsector.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.plugins.ide.idea.model.IdeaModel

/**
 * Starsector 模组构建插件
 * 用于配置和管理 Starsector 模组的构建任务
 *
 * @author Kasuminova
 */
class StarsectorModPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            plugins.apply("idea")

            // 读取 Mod 元数据
            val metadata = ModMetadata(this)

            // 配置目录
            val modProductionDir = layout.buildDirectory.dir("mod_production")
            val contentsDir = projectDir.resolve("contents")

            // 注册各类任务
            ModProductionTasks(this, metadata, modProductionDir, contentsDir).register()
            ModPackagingTasks(this, metadata, modProductionDir).register()
            ModUtilityTasks(this, metadata, modProductionDir).register()
            ModDeploymentTasks(this, metadata, modProductionDir).register()

            val decompileTask = tasks.register("decompileSources", DecompileSourcesTask::class.java) {
                outputDir.set(file("dev-resources/sources"))
            }

            // 配置 IDEA 以索引反编译的源码
            configureIdeaSourceAttachment(decompileTask)
        }
    }

    /**
     * 配置 IDEA 模块，将反编译的源码附加到依赖项
     */
    private fun Project.configureIdeaSourceAttachment(decompileTask: TaskProvider<DecompileSourcesTask>) {
        // 让 IDEA 刷新时自动运行反编译任务
        tasks.named("ideaModule") {
            dependsOn(decompileTask)
        }

        // 配置 IDEA 模块
        extensions.configure<IdeaModel>("idea") {
            // 直接将统一的源码目录添加为源码路径
            val unifiedSourceDir = file("dev-resources/sources")
            module.sourceDirs.add(unifiedSourceDir)
        }
    }
}

package cn.kasuminova.starsector.gradle

import groovy.json.JsonOutput
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.file.Directory
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File

/**
 * Mod 生产目录相关任务
 * 负责创建和管理 mod_production 目录
 */
class ModProductionTasks(
    private val project: Project,
    private val metadata: ModMetadata,
    private val modProductionDir: Provider<Directory>,
    private val contentsDir: File
) {

    fun register() {
        registerCleanTask()
        registerCopyContentTask()
        registerCopyJarsTask()
        registerGenerateModInfoTask()
        registerModProductionTask()
    }

    private fun registerCleanTask() {
        project.tasks.register<Delete>("cleanModProduction") {
            delete(modProductionDir)
            group = "starsector mod"
            description = "清理 Mod 生产目录"
        }
    }

    private fun registerCopyContentTask() {
        project.tasks.register<Sync>("copyContents") {
            from(contentsDir)
            into(modProductionDir)
            preserve {
                include("jars/**")
                include("mod_info.json")
            }
            group = "starsector mod"
            description = "同步静态 Mod 内容到生产目录"
        }
    }

    private fun registerCopyJarsTask() {
        project.tasks.register<Sync>("copyJars") {
            from(project.tasks.withType<Jar>().map { it.archiveFile })
            into(modProductionDir.map { it.dir("jars") })
            dependsOn(project.tasks.withType<Jar>())
            group = "starsector mod"
            description = "同步构建的 JAR 文件到生产目录"
        }
    }

    private fun registerGenerateModInfoTask() {
        project.tasks.register("generateModInfoJson") {
            dependsOn("copyJars", "copyContents")
            group = "starsector mod"
            description = "生成 Starsector 的 mod_info.json 文件"

            inputs.property("mod.id", metadata.modId)
            inputs.property("mod.name", metadata.modName)
            inputs.property("mod.author", metadata.modAuthor)
            inputs.property("mod.description", metadata.modDescription)
            inputs.property("mod.gameVersion", metadata.modGameVersion)
            inputs.property("mod.plugin", metadata.modPlugin)
            inputs.property("mod.dependencies", metadata.modDependencies ?: "")
            inputs.property("version", project.version)
            outputs.file(modProductionDir.map { it.file("mod_info.json") })

            doLast {
                val jarsDir = modProductionDir.get().dir("jars").asFile
                val jarFiles = jarsDir.listFiles()
                    ?.filter { it.extension == "jar" }
                    ?.map { "jars/${it.name}" }
                    ?: emptyList()

                val deps = (metadata.modDependencies ?: "")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { entry ->
                        val parts = entry.split(':', limit = 2)
                        mapOf(
                            "id" to parts.getOrNull(0).orEmpty(),
                            "name" to parts.getOrNull(1).orEmpty()
                        )
                    }

                val modInfo = mapOf(
                    "id" to metadata.modId.get(),
                    "name" to metadata.modName.get(),
                    "author" to metadata.modAuthor.get(),
                    "version" to project.version.toString(),
                    "description" to metadata.modDescription.get(),
                    "gameVersion" to metadata.modGameVersion.get(),
                    "jars" to jarFiles,
                    "modPlugin" to metadata.modPlugin.get(),
                    "dependencies" to deps
                )

                val json = JsonOutput.prettyPrint(JsonOutput.toJson(modInfo))
                modProductionDir.get().file("mod_info.json").asFile.writeText(json)
            }
        }
    }

    private fun registerModProductionTask() {
        project.tasks.register("modProduction") {
            dependsOn("copyContents", "generateModInfoJson")
            group = "starsector mod"
            description = "组装 Starsector Mod 生产目录"
        }
    }
}

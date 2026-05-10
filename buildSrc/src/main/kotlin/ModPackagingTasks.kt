package cn.kasuminova.starsector.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.file.Directory
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression
import org.gradle.kotlin.dsl.register

/**
 * Mod 打包相关任务
 * 负责创建 ZIP 发布包
 */
class ModPackagingTasks(
    private val project: Project,
    private val metadata: ModMetadata,
    private val modProductionDir: Provider<Directory>
) {

    fun register() {
        registerZipTask()
        configureBuildTask()
    }

    private fun registerZipTask() {
        project.tasks.register<Zip>("zipModProduction") {
            dependsOn("modProduction")
            from(modProductionDir)
            archiveFileName.set("${metadata.modName.get()}-${project.version}.zip")
            destinationDirectory.set(project.layout.buildDirectory)
            entryCompression = ZipEntryCompression.DEFLATED
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            group = "starsector mod"
            description = "创建 Mod 生产目录的 ZIP 文件"
        }
    }

    private fun configureBuildTask() {
        project.tasks.named("build") {
            finalizedBy("zipModProduction")
        }
    }
}


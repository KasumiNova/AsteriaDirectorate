package cn.kasuminova.starsector.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

abstract class DecompileSourcesTask : DefaultTask() {

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Input
    abstract val decompilerVersion: Property<String>

    @get:Input
    abstract val maxParallelDecompile: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        description = "Decompiles project dependencies and saves the sources to the output directory."
        group = "ide"
        decompilerVersion.convention("1.9.3")
        maxParallelDecompile.convention(2)
    }

    @TaskAction
    fun execute() {
        // 输出配置信息
        logger.lifecycle("===== 反编译任务配置 =====")
        logger.lifecycle("反编译器版本: ${decompilerVersion.get()}")
        logger.lifecycle("最大并发线程数: ${maxParallelDecompile.get()}")
        logger.lifecycle("========================")

        val decompilerConfig = project.configurations.detachedConfiguration(
            project.dependencies.create("org.vineflower:vineflower:${decompilerVersion.get()}")
        )

        val resolvableCompileOnly = project.configurations.create("resolvableCompileOnly") {
            isCanBeResolved = true
            extendsFrom(project.configurations.getByName("compileOnly"))
        }

        val workQueue = workerExecutor.processIsolation {
            forkOptions.maxHeapSize = "256m"
        }

        workQueue.await()

        // 读取 hash 缓存
        val outputDirectory = outputDir.get().asFile
        val hashCacheFile = outputDirectory.resolve(".decompile-cache.txt")
        val hashCache = loadHashCache(hashCacheFile)
        val newHashCache = mutableMapOf<String, String>()

        val maxWorkers = maxParallelDecompile.get()
        var activeWorkers = 0

        // 创建统一的源码输出目录
        val unifiedSourceDir = outputDirectory
        unifiedSourceDir.mkdirs()

        resolvableCompileOnly.files.forEach { jarFile ->
            if (jarFile.extension == "jar" && !jarFile.name.endsWith("-sources.jar")) {
                // 临时目录用于反编译
                val tempDir = outputDirectory.resolve(".temp/${jarFile.nameWithoutExtension}")

                // 计算 jar 文件的 hash 值
                val currentHash = calculateFileHash(jarFile)
                newHashCache[jarFile.name] = currentHash

                // 检查是否需要重新反编译
                val cachedHash = hashCache[jarFile.name]
                val needsDecompile = cachedHash != currentHash || !unifiedSourceDir.exists()

                if (needsDecompile) {
                    if (cachedHash != null && cachedHash != currentHash) {
                        logger.lifecycle("检测到 ${jarFile.name} 已更新，重新反编译")
                    } else {
                        logger.lifecycle("正在反编译 ${jarFile.name}")
                    }

                    // 清理旧的临时目录
                    if (tempDir.exists()) {
                        tempDir.deleteRecursively()
                    }
                    tempDir.mkdirs()

                    // 等待直到有可用的 worker 槽位
                    if (activeWorkers >= maxWorkers) {
                        workQueue.await()
                        activeWorkers = 0
                    }

                    workQueue.submit(DecompileAction::class.java) {
                        this.decompilerClasspath.from(decompilerConfig)
                        this.targetJar.set(jarFile)
                        this.tempOutputDir.set(tempDir)
                        this.finalOutputDir.set(unifiedSourceDir)
                        this.jarName.set(jarFile.name)
                    }
                    activeWorkers++
                } else {
                    logger.lifecycle("${jarFile.name} 的源码已存在且未更改，跳过反编译")
                }
            }
        }

        workQueue.await()

        // 清理临时目录
        val tempBaseDir = outputDirectory.resolve(".temp")
        if (tempBaseDir.exists()) {
            tempBaseDir.deleteRecursively()
        }

        // 保存更新后的 hash 缓存
        saveHashCache(hashCacheFile, newHashCache)
        logger.lifecycle("反编译完成，源码已统一输出到: ${unifiedSourceDir.absolutePath}")
    }

    /**
     * 计算文件的 SHA-256 hash 值
     */
    private fun calculateFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 从文件加载 hash 缓存
     */
    private fun loadHashCache(cacheFile: File): Map<String, String> {
        if (!cacheFile.exists()) {
            return emptyMap()
        }

        return try {
            cacheFile.readLines()
                .filter { it.isNotBlank() && it.contains("=") }
                .associate {
                    val parts = it.split("=", limit = 2)
                    parts[0].trim() to parts[1].trim()
                }
        } catch (e: Exception) {
            logger.warn("读取 hash 缓存失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 保存 hash 缓存到文件
     */
    private fun saveHashCache(cacheFile: File, cache: Map<String, String>) {
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(
                cache.entries.joinToString("\n") { "${it.key}=${it.value}" }
            )
        } catch (e: Exception) {
            logger.warn("保存 hash 缓存失败: ${e.message}")
        }
    }
}

interface DecompileParameters : WorkParameters {
    val decompilerClasspath: ConfigurableFileCollection
    val targetJar: Property<File>
    val tempOutputDir: DirectoryProperty
    val finalOutputDir: DirectoryProperty
    val jarName: Property<String>
}

abstract class DecompileAction : WorkAction<DecompileParameters> {
    override fun execute() {
        val process = ProcessBuilder(
            "java",
            "-jar",
            parameters.decompilerClasspath.singleFile.absolutePath,
            parameters.targetJar.get().absolutePath,
            parameters.tempOutputDir.get().asFile.absolutePath
        ).redirectErrorStream(true).start()

        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()

        // 将反编译的文件移动到统一的源码目录
        val tempDir = parameters.tempOutputDir.get().asFile
        val finalDir = parameters.finalOutputDir.get().asFile
        tempDir.listFiles()?.forEach { file ->
            file.copyRecursively(finalDir.resolve(file.name), overwrite = true)
        }
    }
}

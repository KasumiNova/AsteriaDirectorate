# 启动与调试（runGame / launch-config.json）

`runGame` 任务由 SDG mod 插件（`io.github.nanoforged.sectordevgradle.mod`）提供，先 `deployMod` 再启动游戏。
启动模式由 `build.gradle.kts` 的 `starsector { launchMode }` 决定：

- `NANOFORGE`（本项目当前值）：launch-spec 前置检查链 → RFB Main + `--tweakClass io.github.nanoforged.NanoForgeBootstrap`，
  JVM 参数由 launch-spec 模板生成，**不读** `launch-config.json`。
- `VANILLA`：读取 `launch-config.json` 的 JVM 参数与类路径，直启 `com.fs.starfarer.StarfarerLauncher`。

## Java 运行时选择

runGame 是 `JavaExec` 任务，JVM 与项目 toolchain 无关：探测到的运行时会以自定义 `JavaLauncher`
注入任务，按以下候选顺序取第一个可用的 `java`：

1. `starsector.javaExec` / `STARSECTOR_JAVA_EXEC`：直接指定 java 可执行文件
2. `starsector.javaHome` / `STARSECTOR_JAVA_HOME` / `JBR17_HOME`：指定 JDK 根目录
3. 游戏目录自带运行时：`zulu25_<os>` 优先，旧版 `jre[_<os>]` 其次
4. `~/.jdks/` 下的 JBR（`jbr-*`，按名称倒序）
5. 当前运行 Gradle 的 JVM 兜底

可再加约束过滤候选（不满足的候选直接跳过，全部不满足则报错并列出探测结果）：

- `starsector.javaVersion=25`（或 `STARSECTOR_JAVA_VERSION`）：限定 Java 主版本号。
  本项目在 `gradle.properties` 固定为 25（BoxUtil 等模组已是 Java 25 字节码，JBR 17 无法加载）。
- `starsector.javaVendor=zulu|jetbrains|temurin|...`（或 `STARSECTOR_JAVA_VENDOR`）：限定发行版，
  `jbr` 是 `jetbrains` 的别名。需要 JBR 热重定义（`-XX:+AllowEnhancedClassRedefinition`）时用它选新版 JBR。

每次启动会在日志打印实际选中的运行时：`SDG: runGame 使用 Java：...（...，vendor=...）`。

## IDEA 调试

runGame 是 `JavaExec` 任务，**直接对 `Starsector runGame` 运行配置点调试按钮即可**：
IDEA 会把调试器注入游戏 fork JVM，断点即刻生效（打勾），改代码后热替换直接作用于游戏进程。

- `genIdeaRuns`（runGame 执行后自动生成）会在 `.run/` 写出 `Starsector-runGame` 与 `Starsector-Attach` 配置。
- CLI / 远程场景备选：`./gradlew runGame -Pstarsector.debug=true` 注入 JDWP
  （默认挂起等待 attach，端口 5005，可用 `starsector.debugPort` 与 `-Pstarsector.debugSuspend=false` 调整），
  再用 `Starsector-Attach` 配置连上。

### 热重定义（改方法体之外的变更）

普通 JVM 热替换只支持改方法体；改类结构（增删方法/字段、改签名）需要 JBR（JetBrains Runtime）：

1. 在 `~/.jdks/` 放入新版 JBR（如 `jbr-25`，IDEA 下载的 JBR 也在此目录）。
2. 启动时加 `-Pstarsector.javaVendor=jetbrains` 选中它。
3. 选中 JBR 后 runGame 会**自动附加** `-XX:+AllowEnhancedClassRedefinition`（已在参数中则不重复），
   IDEA 的 Reload Changed Classes 即可应用结构性变更。

## launch-config.json（仅 VANILLA 模式）

- `jvmArgs.common`：各平台通用 JVM 参数
- `jvmArgs.windows/linux/mac`：平台特定参数（主要是 `java.library.path` 与平台标记）
- `classpath`：启动时所需的 jar 列表（相对游戏根目录）

与运行时不兼容的参数会被自动剥离并打印日志：JDK<24 去掉 `-XX:+UseCompactObjectHeaders`，
非 JBR 去掉 `-XX:+AllowEnhancedClassRedefinition`。

### 常见调整点

- **内存参数**：VANILLA 模板包含较激进的默认值（例如 `-Xms`/`-Xmx` 很大）；内存不足先调小（4G/6G/8G）。
  NANOFORGE 模式堆大小用 `starsector { heap.set("8g") }`（Xms=Xmx，默认 4g）。
- **JVM 版本兼容性**：如遇启动异常，先去掉部分 `-XX:` 高级参数，保留最小集合（编码、库路径、内存）再逐步加回。
- **Native 库路径**：Linux 下通常需要 `-Djava.library.path=./native/linux`，确保游戏目录下 `native/linux` 存在且与游戏版本匹配。

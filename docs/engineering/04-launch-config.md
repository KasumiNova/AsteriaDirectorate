# launch-config.json 说明（启动/调试）

`launch-config.json` 用于描述“启动游戏”所需的 JVM 参数与类路径。
本项目的 `launchGame` 任务会读取该文件（具体实现见 `buildSrc` 里的部署/启动任务）。

## 文件结构

- `jvmArgs.common`：各平台通用 JVM 参数
- `jvmArgs.windows/linux/mac`：平台特定参数（主要是 `java.library.path` 与平台标记）
- `classpath`：启动时所需的 jar 列表（相对游戏根目录）

## 常见调整点

### 内存参数

当前模板包含较激进的默认值（例如 `-Xms`/`-Xmx` 很大）。
如果你的机器内存不足，建议把它们调小（例如 4G/6G/8G），否则可能出现：
- 启动失败
- 系统交换导致卡顿

### JVM 版本

Starsector 不同版本对 JVM/参数的兼容性会有差异。
如果你遇到启动异常：
- 先尝试去掉部分 `-XX:` 高级参数
- 保留最小集合（编码、库路径、内存）再逐步加回

### Native 库路径

Linux 下通常需要：
- `-Djava.library.path=./native/linux`

确保你的游戏目录下 `native/linux` 存在，并且与游戏版本匹配。

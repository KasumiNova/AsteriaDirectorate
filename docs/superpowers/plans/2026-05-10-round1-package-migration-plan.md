# ASTD Round 1 Package Migration Plan

**Goal:** 将主源码与 ss-csv 根包从 `cn.kasuminova.asteriadirectorate` 迁移到 `cn.kasuminova.astd`，并写入新的包结构约定。

**Architecture:** 先做根包迁移与运行时字符串引用修复，保持现有业务目录和功能行为稳定；领域包细分与新射弹 VFX runtime 进入后续轮次。

**Tech Stack:** Java 17, Kotlin, Gradle, Starsector Mod, ss-csv, Markdown skills

---

## Scope

本轮执行：

1. 新增 `package-structure-guidelines` skill。
2. 更新 skill index。
3. 根包批量迁移：
   - `src/main/java`
   - `src/main/kotlin`
   - `src/test/kotlin`
   - `ss-csv/src/main/kotlin`
4. 同步字符串引用：
   - `gradle.properties`
   - `ss-csv/build.gradle.kts`
   - `contents/data/**`
   - `ss-csv/src/**` 中脚本类名字符串
5. 验证：
   - `./gradlew build`
   - `./gradlew :ss-csv:generateSsCsv`
   - 全文检索旧包名

本轮不执行：

- 业务包细分到 `combat.effect.arc/lens` 等目标结构
- 新射弹 VFX runtime
- 前端 Kotlin export 重做
- 旧射弹 VFX 体系替换
- `contents/` 覆盖写回 ss-csv 生成物

---

## Task 1: Add package structure skill checks

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/asteriadirectorate/docs/PackageStructureGuidelinesTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.asteriadirectorate.docs

  import kotlin.io.path.Path
  import kotlin.io.path.exists
  import kotlin.io.path.readText
  import kotlin.test.Test
  import kotlin.test.assertTrue

  class PackageStructureGuidelinesTest {
      private val repoRoot = Path(System.getProperty("user.dir"))

      @Test
      fun `package structure skill exists and is indexed`() {
          val skillPath = repoRoot.resolve(".agents/skills/package-structure-guidelines/SKILL.md")
          val indexPath = repoRoot.resolve(".agents/skills/00-skill-index/SKILL.md")

          assertTrue(skillPath.exists(), "package structure skill file should exist")
          assertTrue(indexPath.readText().contains("package-structure-guidelines"), "skill index should reference package structure skill")
      }

      @Test
      fun `package structure skill contains required package conventions`() {
          val text = repoRoot.resolve(".agents/skills/package-structure-guidelines/SKILL.md").readText()
          val required = listOf(
              "cn.kasuminova.astd",
              "astd.internal",
              "astd.renderer",
              "astd.combat",
              "combat.effect.base",
              "combat.effect.generic",
              "combat.effect.arc",
              "combat.effect.lens",
              "combat.shipsystems.base",
              "combat.skills.base",
              "combat.hullmods.base",
              "combat.affix.base",
          )

          for (needle in required) {
              assertTrue(text.contains(needle), "missing convention: $needle")
          }
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*PackageStructureGuidelinesTest'`
- Expected output:
  ```text
  FAILED PackageStructureGuidelinesTest > package structure skill exists and is indexed
  package structure skill file should exist
  ```

**Step 3: Add skill document**
- File: `.agents/skills/package-structure-guidelines/SKILL.md`
- Code:
  ```markdown
  ---
  name: "package-structure-guidelines"
  description: "ASTD 包结构规范：根包、internal、renderer、combat 及各领域 base 包约定。"
  ---

  # Skill：包结构规范

  ## 根包

  新代码根包统一使用：

  `cn.kasuminova.astd`

  ## 通用工具

  `cn.kasuminova.astd.internal`

  用于存放通用工具、基础设施、非业务特定代码。

  ## 渲染相关

  `cn.kasuminova.astd.renderer`

  展开后包含：

  - `cn.kasuminova.astd.renderer.projectile`
  - `cn.kasuminova.astd.renderer.system`
  - `cn.kasuminova.astd.renderer.effect.projectile`
  - `cn.kasuminova.astd.renderer.effect.skill`
  - `cn.kasuminova.astd.renderer.effect.hullmods`
  - `cn.kasuminova.astd.renderer.effect.affix`

  ## 战斗相关

  `cn.kasuminova.astd.combat`

  射弹/武器特效：

  - `cn.kasuminova.astd.combat.effect.base`
  - `cn.kasuminova.astd.combat.effect.generic`
  - `cn.kasuminova.astd.combat.effect.arc`
  - `cn.kasuminova.astd.combat.effect.lens`

  舰船系统：

  - `cn.kasuminova.astd.combat.shipsystems`
  - `cn.kasuminova.astd.combat.shipsystems.base`

  军官技能：

  - `cn.kasuminova.astd.combat.skills`
  - `cn.kasuminova.astd.combat.skills.base`

  舰船插件：

  - `cn.kasuminova.astd.combat.hullmods`
  - `cn.kasuminova.astd.combat.hullmods.base`
  - `cn.kasuminova.astd.combat.hullmods.affix`

  ASTD Affix 体系：

  - `cn.kasuminova.astd.combat.affix`
  - `cn.kasuminova.astd.combat.affix.base`

  ## 文件组织

  - 每个特效使用单一 Kt 文件，即便可能存在多个类。
  - 每个系统、技能、Hullmod、Affix 使用单一 Kt 文件，即便可能存在多个类。
  - 系统、技能、Hullmod、Affix 的具体实现文件中不放抽象类。
  - 抽象类和通用接口放入各领域 `base` 包。
  - 涉及渲染的额外实现可以放入 `cn.kasuminova.astd.renderer.effect.xxx`。
  - `ss-csv` 项目需要安排结构重构；第一轮优先完成包名迁移。
  ```

**Step 4: Update skill index**
- File: `.agents/skills/00-skill-index/SKILL.md`
- Change:
  ```markdown
  ## 包结构

  - **包结构规范**
    - 路径：`.agents/skills/package-structure-guidelines/SKILL.md`
    - 适用：根包、internal、renderer、combat、base 包与单文件组织约定。
  ```

**Step 5: Run test and verify success**
- Command: `./gradlew test --tests '*PackageStructureGuidelinesTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 2: Migrate main source root package

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/asteriadirectorate/docs/RootPackageMigrationTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.asteriadirectorate.docs

  import java.nio.file.Files
  import kotlin.io.path.Path
  import kotlin.io.path.isRegularFile
  import kotlin.io.path.readText
  import kotlin.test.Test
  import kotlin.test.assertFalse
  import kotlin.test.assertTrue

  class RootPackageMigrationTest {
      private val repoRoot = Path(System.getProperty("user.dir"))
      private val sourceRoots = listOf("src/main/java", "src/main/kotlin", "src/test/kotlin")

      @Test
      fun `main and test sources use new root package`() {
          val files = sourceRoots.flatMap { root ->
              val path = repoRoot.resolve(root)
              if (Files.exists(path)) Files.walk(path).use { stream -> stream.filter { it.isRegularFile() }.toList() } else emptyList()
          }

          val sourceText = files.joinToString("\n") { it.readText() }
          assertFalse(sourceText.contains("cn.kasuminova.asteriadirectorate"), "source still contains old root package")
          assertTrue(sourceText.contains("cn.kasuminova.astd"), "source should contain new root package")
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*RootPackageMigrationTest'`
- Expected output:
  ```text
  FAILED RootPackageMigrationTest > main and test sources use new root package
  source still contains old root package
  ```

**Step 3: Replace package/import declarations**
- Files:
  - `src/main/java/**/*.java`
  - `src/main/kotlin/**/*.kt`
  - `src/test/kotlin/**/*.kt`
- Replace:
  - `package cn.kasuminova.asteriadirectorate` → `package cn.kasuminova.astd`
  - `import cn.kasuminova.asteriadirectorate` → `import cn.kasuminova.astd`

**Step 4: Move source directories**
- Move:
  - `src/main/java/cn/kasuminova/asteriadirectorate/**` → `src/main/java/cn/kasuminova/astd/**`
  - `src/main/kotlin/cn/kasuminova/asteriadirectorate/**` → `src/main/kotlin/cn/kasuminova/astd/**`
  - `src/test/kotlin/cn/kasuminova/asteriadirectorate/**` → `src/test/kotlin/cn/kasuminova/astd/**`

**Step 5: Update new test package names**
- Files:
  - `src/test/kotlin/cn/kasuminova/astd/docs/PackageStructureGuidelinesTest.kt`
  - `src/test/kotlin/cn/kasuminova/astd/docs/RootPackageMigrationTest.kt`
- Replace:
  - `package cn.kasuminova.asteriadirectorate.docs` → `package cn.kasuminova.astd.docs`

**Step 6: Run test and verify success**
- Command: `./gradlew test --tests '*RootPackageMigrationTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 3: Migrate ss-csv root package

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/docs/SsCsvPackageMigrationTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.astd.docs

  import java.nio.file.Files
  import kotlin.io.path.Path
  import kotlin.io.path.isRegularFile
  import kotlin.io.path.readText
  import kotlin.test.Test
  import kotlin.test.assertFalse
  import kotlin.test.assertTrue

  class SsCsvPackageMigrationTest {
      private val repoRoot = Path(System.getProperty("user.dir"))

      @Test
      fun `ss csv source and gradle use new package`() {
          val files = listOf(repoRoot.resolve("ss-csv/src/main/kotlin"), repoRoot.resolve("ss-csv/build.gradle.kts")).flatMap { root ->
              if (Files.isDirectory(root)) Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() } else listOf(root)
          }
          val text = files.joinToString("\n") { it.readText() }
          assertFalse(text.contains("cn.kasuminova.asteriadirectorate.sscsv"), "ss-csv still contains old package")
          assertTrue(text.contains("cn.kasuminova.astd.sscsv"), "ss-csv should contain new package")
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*SsCsvPackageMigrationTest'`
- Expected output:
  ```text
  FAILED SsCsvPackageMigrationTest > ss csv source and gradle use new package
  ss-csv still contains old package
  ```

**Step 3: Replace ss-csv package/import declarations**
- Files:
  - `ss-csv/src/main/kotlin/**/*.kt`
- Replace:
  - `cn.kasuminova.asteriadirectorate.sscsv` → `cn.kasuminova.astd.sscsv`

**Step 4: Move ss-csv directory**
- Move:
  - `ss-csv/src/main/kotlin/cn/kasuminova/asteriadirectorate/sscsv/**`
  - to `ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/**`

**Step 5: Update ss-csv Gradle entry points**
- File: `ss-csv/build.gradle.kts`
- Replace:
  - `cn.kasuminova.asteriadirectorate.sscsv.gen.SsCsvGeneratorKt` → `cn.kasuminova.astd.sscsv.gen.SsCsvGeneratorKt`
  - `cn.kasuminova.asteriadirectorate.sscsv.entries.catalog` → `cn.kasuminova.astd.sscsv.entries.catalog`

**Step 6: Run test and verify success**
- Command: `./gradlew test --tests '*SsCsvPackageMigrationTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 4: Migrate runtime script references

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/docs/RuntimeScriptReferenceMigrationTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.astd.docs

  import java.nio.file.Files
  import kotlin.io.path.Path
  import kotlin.io.path.isRegularFile
  import kotlin.io.path.readText
  import kotlin.test.Test
  import kotlin.test.assertFalse
  import kotlin.test.assertTrue

  class RuntimeScriptReferenceMigrationTest {
      private val repoRoot = Path(System.getProperty("user.dir"))

      @Test
      fun `runtime script references use new root package`() {
          val roots = listOf(
              repoRoot.resolve("gradle.properties"),
              repoRoot.resolve("contents/data"),
              repoRoot.resolve("ss-csv/src/main/kotlin"),
          )
          val files = roots.flatMap { root ->
              if (Files.isDirectory(root)) Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() } else listOf(root)
          }
          val text = files.joinToString("\n") { it.readText() }
          assertFalse(text.contains("cn.kasuminova.asteriadirectorate"), "runtime references still contain old root package")
          assertTrue(text.contains("mod.plugin=cn.kasuminova.astd.AsteriaDirectoratePlugin"), "mod plugin should use new root package")
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*RuntimeScriptReferenceMigrationTest'`
- Expected output:
  ```text
  FAILED RuntimeScriptReferenceMigrationTest > runtime script references use new root package
  runtime references still contain old root package
  ```

**Step 3: Update runtime strings**
- Files:
  - `gradle.properties`
  - `contents/data/**`
  - `ss-csv/src/main/kotlin/**`
- Replace:
  - `cn.kasuminova.asteriadirectorate` → `cn.kasuminova.astd`

**Step 4: Verify dispatcher default strings**
- File: `ss-csv/src/main/kotlin/cn/kasuminova/astd/sscsv/outputs/proj/ProjProjectileSpec.kt`
- Expected values:
  - `cn.kasuminova.astd.weapons.common.ProjectileSpecOnFireDispatcher`

**Step 5: Run test and verify success**
- Command: `./gradlew test --tests '*RuntimeScriptReferenceMigrationTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 5: Build main project

**Step 1: Run full build**
- Command: `./gradlew build`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 2: Fix any compiler failures**
- Common failures:
  - missed import migration
  - missed package migration
  - Java/Kotlin cross-reference mismatch
  - resource script string mismatch
- After each fix, rerun:
  - `./gradlew build`

**Step 3: Keep changes scoped**
- Expected diff categories:
  - package declarations
  - import paths
  - runtime class name strings
  - directory moves
  - skill files
  - migration checks

---

## Task 6: Verify ss-csv generation

**Step 1: Run safe generation**
- Command: `./gradlew :ss-csv:generateSsCsv`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 2: Check generated output for old package**
- Command: `grep -R "cn.kasuminova.asteriadirectorate" build/generated/ss-csv || true`
- Expected output:
  ```text
  ```

**Step 3: Check generated dispatcher references**
- Command: `grep -R "ProjectileSpecOnFireDispatcher" build/generated/ss-csv/data || true`
- Expected output contains:
  ```text
  cn.kasuminova.astd.weapons.common.ProjectileSpecOnFireDispatcher
  ```

---

## Task 7: Final old-package search

**Step 1: Search active source/config paths**
- Command: `grep -R "cn.kasuminova.asteriadirectorate" src ss-csv contents gradle.properties build.gradle.kts settings.gradle.kts .agents/skills || true`
- Expected output:
  ```text
  ```

**Step 2: Inspect changed files**
- Command: `git --no-pager diff --stat`
- Expected scope:
  - root package migration
  - ss-csv package migration
  - skill additions
  - migration tests

**Step 3: Confirm no new projectile VFX feature code**
- Command: `git --no-pager diff -- src/main src/test ss-csv .agents/skills contents gradle.properties | grep -E "ASTDProjectileVfxRuntime|ASTDProjectileVfxPreset|RibbonLayer|GlowLayer|HeadTrailLayer" || true`
- Expected output:
  ```text
  ```

---

## Acceptance Criteria

- `cn.kasuminova.astd` is the main source root package.
- `cn.kasuminova.astd.sscsv` is the ss-csv root package.
- Runtime script strings in `contents/data/**` point to the new package.
- `mod.plugin` points to `cn.kasuminova.astd.AsteriaDirectoratePlugin`.
- `package-structure-guidelines` skill exists and is indexed.
- `./gradlew build` succeeds.
- `./gradlew :ss-csv:generateSsCsv` succeeds.
- Active source/config paths contain no old root package string.
- No new projectile VFX runtime feature is implemented in this round.

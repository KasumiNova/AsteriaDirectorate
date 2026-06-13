# ASTD Round 2 Domain Package Structure Plan

**Goal:** 在第一轮根包迁移完成后，将现有业务代码按 `internal`、`renderer`、`combat` 三大域重组，保持运行时行为稳定。

**Architecture:** 纯结构重组；先用检查测试锁定目标包结构，再分领域迁移文件、更新 imports 与资源脚本引用，最后通过构建和 ss-csv 安全生成验证。

**Tech Stack:** Java 17, Kotlin, Gradle, Starsector Mod, ss-csv

---

## Scope

本轮执行：

1. 迁移现有业务包到目标领域包。
2. 创建各领域 `base` 包。
3. 抽象类、接口、通用基类进入对应 `base` 包。
4. 更新 `contents/data/**` 运行时脚本引用。
5. 更新 `ss-csv/src/**` 默认脚本引用。
6. 更新 skill 中新增 `combat.effect.psi` 包约定。
7. 验证主构建和 ss-csv 安全生成。

本轮范围外：

- 新射弹 VFX runtime
- 前端 Kotlin export 重做
- 旧射弹 VFX 体系替换
- ss-csv 内容模型重构
- Affix 业务逻辑重写

---

## Target package mapping

| Current package | Target package |
| --- | --- |
| `cn.kasuminova.astd.util` | `cn.kasuminova.astd.internal` / `cn.kasuminova.astd.internal.i18n` |
| `cn.kasuminova.astd.skills` | `cn.kasuminova.astd.combat.skills` |
| `cn.kasuminova.astd.shipsystems` | `cn.kasuminova.astd.combat.shipsystems` |
| `cn.kasuminova.astd.hullmods` | `cn.kasuminova.astd.combat.hullmods` |
| `cn.kasuminova.astd.hullmods.affix` | `cn.kasuminova.astd.combat.hullmods.affix` |
| `cn.kasuminova.astd.weapons.arc` | `cn.kasuminova.astd.combat.effect.arc` |
| `cn.kasuminova.astd.weapons.lens` | `cn.kasuminova.astd.combat.effect.lens` |
| `cn.kasuminova.astd.weapons.psi` | `cn.kasuminova.astd.combat.effect.psi` |
| `cn.kasuminova.astd.weapons.shared` | `cn.kasuminova.astd.combat.effect.generic` |
| `cn.kasuminova.astd.weapons.common.effects` | `cn.kasuminova.astd.combat.effect.generic` |
| `cn.kasuminova.astd.weapons.common.projectile` | `cn.kasuminova.astd.combat.effect.generic.projectile` |
| `cn.kasuminova.astd.weapons.common.boxutil` | `cn.kasuminova.astd.renderer.boxutil` |
| `cn.kasuminova.astd.weapons.common.beam` | `cn.kasuminova.astd.renderer.effect.projectile.beam` |
| `cn.kasuminova.astd.combat.vfx` | `cn.kasuminova.astd.renderer.effect.system` / `cn.kasuminova.astd.renderer.effect.hullmods` |

---

## Task 1: Update package structure skill for psi domain

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/docs/PackageStructureGuidelinesPsiTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.astd.docs

  import kotlin.io.path.Path
  import kotlin.io.path.readText
  import kotlin.test.Test
  import kotlin.test.assertTrue

  class PackageStructureGuidelinesPsiTest {
      private val repoRoot = Path(System.getProperty("user.dir"))

      @Test
      fun `package structure skill documents psi combat effect package`() {
          val text = repoRoot.resolve(".agents/skills/package-structure-guidelines/SKILL.md").readText()
          assertTrue(text.contains("combat.effect.psi"), "psi effect package should be documented")
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*PackageStructureGuidelinesPsiTest'`
- Expected output:
  ```text
  FAILED PackageStructureGuidelinesPsiTest > package structure skill documents psi combat effect package
  psi effect package should be documented
  ```

**Step 3: Update skill document**
- File: `.agents/skills/package-structure-guidelines/SKILL.md`
- Add under combat effects:
  ```markdown
  - `cn.kasuminova.astd.combat.effect.psi`
  ```

**Step 4: Run test and verify success**
- Command: `./gradlew test --tests '*PackageStructureGuidelinesPsiTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 2: Add package structure regression checks

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/docs/CombatPackageStructureTest.kt`
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

  class CombatPackageStructureTest {
      private val repoRoot = Path(System.getProperty("user.dir"))
      private val srcMain = listOf(repoRoot.resolve("src/main/java"), repoRoot.resolve("src/main/kotlin"))

      @Test
      fun `old top level business packages are migrated`() {
          val text = readMainSourceText()
          val forbidden = listOf(
              "package cn.kasuminova.astd.weapons",
              "package cn.kasuminova.astd.shipsystems",
              "package cn.kasuminova.astd.skills",
              "package cn.kasuminova.astd.hullmods",
              "package cn.kasuminova.astd.combat.vfx",
          )
          for (needle in forbidden) {
              assertFalse(text.contains(needle), "old package remains: $needle")
          }
      }

      @Test
      fun `new domain packages exist`() {
          val text = readMainSourceText()
          val required = listOf(
              "package cn.kasuminova.astd.internal",
              "package cn.kasuminova.astd.renderer",
              "package cn.kasuminova.astd.combat.effect.base",
              "package cn.kasuminova.astd.combat.effect.generic",
              "package cn.kasuminova.astd.combat.effect.arc",
              "package cn.kasuminova.astd.combat.effect.lens",
              "package cn.kasuminova.astd.combat.effect.psi",
              "package cn.kasuminova.astd.combat.shipsystems",
              "package cn.kasuminova.astd.combat.skills",
              "package cn.kasuminova.astd.combat.hullmods",
              "package cn.kasuminova.astd.combat.affix",
          )
          for (needle in required) {
              assertTrue(text.contains(needle), "missing target package: $needle")
          }
      }

      private fun readMainSourceText(): String = srcMain.flatMap { root ->
          if (Files.exists(root)) Files.walk(root).use { stream -> stream.filter { it.isRegularFile() }.toList() } else emptyList()
      }.joinToString("\n") { it.readText() }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*CombatPackageStructureTest'`
- Expected output:
  ```text
  FAILED CombatPackageStructureTest > old top level business packages are migrated
  old package remains: package cn.kasuminova.astd.weapons
  ```

---

## Task 3: Migrate internal utilities

**Step 1: Move utility files**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/util/I18n.kt` → `src/main/kotlin/cn/kasuminova/astd/internal/i18n/I18n.kt`
  - `src/main/kotlin/cn/kasuminova/astd/util/I18nUi.kt` → `src/main/kotlin/cn/kasuminova/astd/internal/i18n/I18nUi.kt`
  - `src/main/kotlin/cn/kasuminova/astd/util/Utils.kt` → `src/main/kotlin/cn/kasuminova/astd/internal/Utils.kt`

**Step 2: Update package declarations**
- Files:
  - `src/main/kotlin/cn/kasuminova/astd/internal/i18n/I18n.kt`
  - `src/main/kotlin/cn/kasuminova/astd/internal/i18n/I18nUi.kt`
  - `src/main/kotlin/cn/kasuminova/astd/internal/Utils.kt`
- Expected packages:
  ```kotlin
  package cn.kasuminova.astd.internal.i18n
  package cn.kasuminova.astd.internal
  ```

**Step 3: Update imports**
- Replace in `src/main/**/*.kt` and `src/test/**/*.kt`:
  - `cn.kasuminova.astd.util.I18n` → `cn.kasuminova.astd.internal.i18n.I18n`
  - `cn.kasuminova.astd.util.I18nUi` → `cn.kasuminova.astd.internal.i18n.I18nUi`
  - `cn.kasuminova.astd.util.Utils` → `cn.kasuminova.astd.internal.Utils`

**Step 4: Run targeted tests**
- Command: `./gradlew test --tests '*HullModDescriptionFormatTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 4: Migrate ship systems and skills

**Step 1: Move ship system files**
- Move directory:
  - `src/main/kotlin/cn/kasuminova/astd/shipsystems/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/**`

**Step 2: Move skill files**
- Move directory:
  - `src/main/kotlin/cn/kasuminova/astd/skills/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/skills/**`

**Step 3: Create base package placeholders only if needed by moved abstractions**
- Target directories:
  - `src/main/kotlin/cn/kasuminova/astd/combat/shipsystems/base/`
  - `src/main/kotlin/cn/kasuminova/astd/combat/skills/base/`
- Move shared abstract/interface code into these packages when present.

**Step 4: Update package declarations and imports**
- Replace:
  - `package cn.kasuminova.astd.shipsystems` → `package cn.kasuminova.astd.combat.shipsystems`
  - `package cn.kasuminova.astd.skills` → `package cn.kasuminova.astd.combat.skills`
  - `import cn.kasuminova.astd.shipsystems` → `import cn.kasuminova.astd.combat.shipsystems`
  - `import cn.kasuminova.astd.skills` → `import cn.kasuminova.astd.combat.skills`

**Step 5: Update runtime strings**
- Replace in `contents/data/**` and `ss-csv/src/**`:
  - `cn.kasuminova.astd.shipsystems` → `cn.kasuminova.astd.combat.shipsystems`
  - `cn.kasuminova.astd.skills` → `cn.kasuminova.astd.combat.skills`

**Step 6: Run build**
- Command: `./gradlew build`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 5: Migrate hullmods and affix support hullmods

**Step 1: Move hullmod files**
- Move directory:
  - `src/main/kotlin/cn/kasuminova/astd/hullmods/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/**`

**Step 2: Preserve affix support hullmod package**
- Target:
  - `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/affix/**`

**Step 3: Create base package placeholders only if needed by moved abstractions**
- Target directory:
  - `src/main/kotlin/cn/kasuminova/astd/combat/hullmods/base/`

**Step 4: Update package declarations and imports**
- Replace:
  - `package cn.kasuminova.astd.hullmods` → `package cn.kasuminova.astd.combat.hullmods`
  - `import cn.kasuminova.astd.hullmods` → `import cn.kasuminova.astd.combat.hullmods`

**Step 5: Update runtime strings**
- Replace in `contents/data/**` and `ss-csv/src/**`:
  - `cn.kasuminova.astd.hullmods` → `cn.kasuminova.astd.combat.hullmods`

**Step 6: Run hullmod tests**
- Command: `./gradlew test --tests '*HullModDescriptionFormatTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 6: Migrate combat affix definitions

**Step 1: Identify pure affix model files**
- Inspect files under:
  - `src/main/kotlin/cn/kasuminova/astd/campaign/bounty/`
- Candidate files:
  - `AffixRegistry.kt`
  - Affix data/model files if present

**Step 2: Move pure combat affix definitions**
- Move pure affix model/registry code to:
  - `src/main/kotlin/cn/kasuminova/astd/combat/affix/**`
- Move abstract/interface code to:
  - `src/main/kotlin/cn/kasuminova/astd/combat/affix/base/**`

**Step 3: Keep campaign orchestration in campaign package**
- Keep fleet composition, bounty state, campaign manager, reward logic under:
  - `cn.kasuminova.astd.campaign.bounty`

**Step 4: Update imports**
- Replace affected imports with:
  - `cn.kasuminova.astd.combat.affix`
  - `cn.kasuminova.astd.combat.affix.base`

**Step 5: Run build**
- Command: `./gradlew build`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 7: Migrate weapon and projectile effect packages

**Step 1: Move arc effects**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/arc/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/effect/arc/**`

**Step 2: Move lens effects**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/lens/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/effect/lens/**`

**Step 3: Move psi effects**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/psi/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/effect/psi/**`

**Step 4: Move shared and generic effects**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/shared/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/**`
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/common/effects/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/**`

**Step 5: Move current projectile VFX infrastructure**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/common/projectile/**`
  - to `src/main/kotlin/cn/kasuminova/astd/combat/effect/generic/projectile/**`

**Step 6: Update package declarations and imports**
- Replace common prefixes:
  - `cn.kasuminova.astd.weapons.arc` → `cn.kasuminova.astd.combat.effect.arc`
  - `cn.kasuminova.astd.weapons.lens` → `cn.kasuminova.astd.combat.effect.lens`
  - `cn.kasuminova.astd.weapons.psi` → `cn.kasuminova.astd.combat.effect.psi`
  - `cn.kasuminova.astd.weapons.shared` → `cn.kasuminova.astd.combat.effect.generic`
  - `cn.kasuminova.astd.weapons.common.effects` → `cn.kasuminova.astd.combat.effect.generic`
  - `cn.kasuminova.astd.weapons.common.projectile` → `cn.kasuminova.astd.combat.effect.generic.projectile`

**Step 7: Update runtime strings**
- Replace in `contents/data/**` and `ss-csv/src/**` using the same mappings.

**Step 8: Run build**
- Command: `./gradlew build`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 8: Migrate renderer-oriented common packages

**Step 1: Move BoxUtil wrapper files**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/common/boxutil/**`
  - to `src/main/kotlin/cn/kasuminova/astd/renderer/boxutil/**`

**Step 2: Move beam renderer files**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/common/beam/**`
  - to `src/main/kotlin/cn/kasuminova/astd/renderer/effect/projectile/beam/**`

**Step 3: Move debug helpers**
- Move:
  - `src/main/kotlin/cn/kasuminova/astd/weapons/common/debug/**`
  - to `src/main/kotlin/cn/kasuminova/astd/internal/debug/**`

**Step 4: Move combat vfx renderers**
- Move pure renderer files from:
  - `src/main/kotlin/cn/kasuminova/astd/combat/vfx/**`
- To one of:
  - `src/main/kotlin/cn/kasuminova/astd/renderer/effect/system/**`
  - `src/main/kotlin/cn/kasuminova/astd/renderer/effect/hullmods/**`

**Step 5: Update package declarations and imports**
- Replace:
  - `cn.kasuminova.astd.weapons.common.boxutil` → `cn.kasuminova.astd.renderer.boxutil`
  - `cn.kasuminova.astd.weapons.common.beam` → `cn.kasuminova.astd.renderer.effect.projectile.beam`
  - `cn.kasuminova.astd.weapons.common.debug` → `cn.kasuminova.astd.internal.debug`

**Step 6: Run build**
- Command: `./gradlew build`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 9: Validate target package structure

**Step 1: Run package structure tests**
- Command: `./gradlew test --tests '*CombatPackageStructureTest' --tests '*PackageStructureGuidelinesPsiTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 2: Search for old top-level business packages**
- Command: `grep -R "package cn.kasuminova.astd\.\(weapons\|shipsystems\|skills\|hullmods\|combat\.vfx\)" src/main src/test || true`
- Expected output:
  ```text
  ```

**Step 3: Search runtime strings**
- Command: `grep -R "cn.kasuminova.astd\.\(weapons\|shipsystems\|skills\|hullmods\)" contents ss-csv/src || true`
- Expected output:
  ```text
  ```

---

## Task 10: Verify build and ss-csv generation

**Step 1: Run full build**
- Command: `./gradlew build`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 2: Run safe ss-csv generation**
- Command: `./gradlew :ss-csv:generateSsCsv`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 3: Check generated output for old business package references**
- Command: `grep -R "cn.kasuminova.astd\.\(weapons\|shipsystems\|skills\|hullmods\)" build/generated/ss-csv || true`
- Expected output:
  ```text
  ```

---

## Acceptance Criteria

- Existing business code is organized under `internal`, `renderer`, and `combat` domains.
- Old top-level packages are absent from active source package declarations:
  - `weapons`
  - `shipsystems`
  - `skills`
  - `hullmods`
  - `combat.vfx`
- Runtime script strings in `contents/data/**` and `ss-csv/src/**` point to the new domain packages.
- `combat.effect.psi` is documented in package structure skill.
- `./gradlew build` succeeds.
- `./gradlew :ss-csv:generateSsCsv` succeeds.
- New projectile VFX runtime code is reserved for the next round.

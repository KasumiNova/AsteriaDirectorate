# ASTD Round 4 Projectile VFX Runtime Plan

**Goal:** 实现游戏内 BoxUtil `TrailEntity` 版 projectile VFX runtime，用真实 projectile 历史轨迹驱动 Trail / Glow / Ribbon / HeadTrail 层，为下一轮替换旧射弹渲染体系做准备。

**Architecture:** `renderer.projectile` 提供数据模型、历史采样、TrailEntity 生命周期和 runtime；现有 `combat.effect.generic.projectile` 分发体系暂时保持，只在本轮新增新 runtime 能力和测试，不替换旧体系。

**Tech Stack:** Kotlin, Java 17, Gradle, BoxUtil `TrailEntity`, Starsector Combat API

---

## Scope

本轮执行：

1. 新增游戏内 VFX 数据模型。
2. 新增真实 projectile 历史采样器。
3. 新增 runtime 生命周期模型。
4. 新增 BoxUtil TrailEntity 层构建接口。
5. 实现首版 Trail / Glow / Ribbon / HeadTrail 映射。
6. 建立配置 preset → runtime spec 的入口。
7. 加测试覆盖非直线路径、采样窗口、淡出生命周期、导出类名对齐。
8. 保留旧 projectile VFX 体系，不替换 `.proj` / registry 入口。

本轮范围外：

- 旧体系一次性替换
- `.proj` / `astd_projectile_vfx.json` 正式切到新 runtime
- SpriteEntity / FlareEntity / 实例粒子
- 前端新增字段
- ss-csv 项目结构重构
- BoxUtil 源码修改

---

## Target package

新增：

- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPreset.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntime.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileHistory.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxLayer.kt`
- `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxTrailEntities.kt`

测试：

- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileHistoryTest.kt`
- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetTest.kt`
- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntimeTest.kt`
- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxTrailEntitiesTest.kt`
- `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxExportCompatibilityTest.kt`

---

## Task 1: Add preset and policy model tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPresetTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.astd.renderer.projectile

  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertFalse
  import kotlin.test.assertTrue

  class ASTDProjectileVfxPresetTest {
      @Test
      fun `preset contains runtime vfx policies and supported trail layers`() {
          val preset = ASTDProjectileVfxPreset(
              id = "astd_test",
              layers = listOf(
                  ASTDProjectileVfxLayer.Trail(id = "trail", width = 8f, length = ASTDProjectileVfxLengthPolicy.Fixed(160f), color = ASTDColor(0.4f, 0.8f, 1f, 0.9f)),
                  ASTDProjectileVfxLayer.Glow(id = "glow", width = 18f, length = ASTDProjectileVfxLengthPolicy.VelocityScaled(0.12f), color = ASTDColor(0.1f, 0.5f, 1f, 0.5f)),
                  ASTDProjectileVfxLayer.Ribbon(id = "ribbon", width = 4f, length = ASTDProjectileVfxLengthPolicy.Fixed(120f), color = ASTDColor(1f, 1f, 1f, 0.7f), frequency = 8f, amplitude = 6f),
                  ASTDProjectileVfxLayer.HeadTrail(id = "head", width = 10f, length = ASTDProjectileVfxLengthPolicy.LifetimeWindow(0.08f), color = ASTDColor(0.8f, 1f, 1f, 1f)),
              ),
              samplingPolicy = ASTDProjectileVfxSamplingPolicy(historyFps = 60f, maxHistoryNodes = 96, minDistancePerNode = 2f, smoothingPasses = 1, distanceWindow = 260f),
              fadePolicy = ASTDProjectileVfxFadePolicy(fadeInSeconds = 0f, fadeOutSeconds = 0.16f, hitFadeOutSeconds = 0.10f, expireFadeOutSeconds = 0.20f),
          )

          assertEquals("astd_test", preset.id)
          assertEquals(4, preset.layers.size)
          assertTrue(preset.layers[0] is ASTDProjectileVfxLayer.Trail)
          assertTrue(preset.layers[1] is ASTDProjectileVfxLayer.Glow)
          assertTrue(preset.layers[2] is ASTDProjectileVfxLayer.Ribbon)
          assertTrue(preset.layers[3] is ASTDProjectileVfxLayer.HeadTrail)
      }

      @Test
      fun `runtime preset type names exclude preview only concepts`() {
          val runtimeNames = listOf(
              ASTDProjectileVfxPreset::class.simpleName.orEmpty(),
              ASTDProjectileVfxSamplingPolicy::class.simpleName.orEmpty(),
              ASTDProjectileVfxFadePolicy::class.simpleName.orEmpty(),
              ASTDProjectileVfxLengthPolicy::class.simpleName.orEmpty(),
          ).joinToString("\n")

          listOf("Timeline", "Simulation", "PreviewCamera", "ProjectileVelocity", "Curve", "Loop").forEach { forbidden ->
              assertFalse(runtimeNames.contains(forbidden), "preview-only concept leaked into runtime type names: $forbidden")
          }
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ASTDProjectileVfxPresetTest'`
- Expected output:
  ```text
  FAILED ASTDProjectileVfxPresetTest
  Unresolved reference: ASTDProjectileVfxPreset
  ```

---

## Task 2: Implement preset and policy data model

**Step 1: Add runtime model files**
- File: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxPreset.kt`
- Required definitions:
  ```kotlin
  package cn.kasuminova.astd.renderer.projectile

  data class ASTDProjectileVfxPreset(
      val id: String,
      val layers: List<ASTDProjectileVfxLayer>,
      val samplingPolicy: ASTDProjectileVfxSamplingPolicy,
      val fadePolicy: ASTDProjectileVfxFadePolicy,
  )

  data class ASTDColor(val red: Float, val green: Float, val blue: Float, val alpha: Float)

  data class ASTDProjectileVfxSamplingPolicy(
      val historyFps: Float,
      val maxHistoryNodes: Int,
      val minDistancePerNode: Float,
      val smoothingPasses: Int,
      val distanceWindow: Float,
  )

  data class ASTDProjectileVfxFadePolicy(
      val fadeInSeconds: Float,
      val fadeOutSeconds: Float,
      val hitFadeOutSeconds: Float,
      val expireFadeOutSeconds: Float,
  )

  sealed interface ASTDProjectileVfxLengthPolicy {
      data class Fixed(val worldUnits: Float) : ASTDProjectileVfxLengthPolicy
      data class VelocityScaled(val seconds: Float) : ASTDProjectileVfxLengthPolicy
      data class ProjectileRangeRatio(val ratio: Float) : ASTDProjectileVfxLengthPolicy
      data class LifetimeWindow(val seconds: Float) : ASTDProjectileVfxLengthPolicy
  }
  ```

- File: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxLayer.kt`
- Required definitions:
  ```kotlin
  package cn.kasuminova.astd.renderer.projectile

  sealed interface ASTDProjectileVfxLayer {
      val id: String
      val width: Float
      val length: ASTDProjectileVfxLengthPolicy
      val color: ASTDColor

      data class Trail(
          override val id: String,
          override val width: Float,
          override val length: ASTDProjectileVfxLengthPolicy,
          override val color: ASTDColor,
      ) : ASTDProjectileVfxLayer

      data class Glow(
          override val id: String,
          override val width: Float,
          override val length: ASTDProjectileVfxLengthPolicy,
          override val color: ASTDColor,
      ) : ASTDProjectileVfxLayer

      data class Ribbon(
          override val id: String,
          override val width: Float,
          override val length: ASTDProjectileVfxLengthPolicy,
          override val color: ASTDColor,
          val frequency: Float,
          val amplitude: Float,
      ) : ASTDProjectileVfxLayer

      data class HeadTrail(
          override val id: String,
          override val width: Float,
          override val length: ASTDProjectileVfxLengthPolicy,
          override val color: ASTDColor,
      ) : ASTDProjectileVfxLayer
  }
  ```

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ASTDProjectileVfxPresetTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 3: Add projectile history sampling tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileHistoryTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.astd.renderer.projectile

  import org.lwjgl.util.vector.Vector2f
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  class ASTDProjectileHistoryTest {
      @Test
      fun `history samples by minimum distance`() {
          val history = ASTDProjectileHistory(minDistancePerNode = 5f, maxHistoryNodes = 10, distanceWindow = 100f)
          history.advance(Vector2f(0f, 0f), facing = 0f, elapsed = 0f)
          history.advance(Vector2f(2f, 0f), facing = 0f, elapsed = 0.1f)
          history.advance(Vector2f(5f, 0f), facing = 0f, elapsed = 0.2f)

          assertEquals(2, history.nodes().size)
      }

      @Test
      fun `history preserves non linear path nodes`() {
          val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 10, distanceWindow = 100f)
          history.advance(Vector2f(0f, 0f), facing = 0f, elapsed = 0f)
          history.advance(Vector2f(10f, 0f), facing = 0f, elapsed = 0.1f)
          history.advance(Vector2f(10f, 10f), facing = 90f, elapsed = 0.2f)

          val nodes = history.nodes()
          assertEquals(3, nodes.size)
          assertEquals(10f, nodes[1].location.x)
          assertEquals(0f, nodes[1].location.y)
          assertEquals(10f, nodes[2].location.x)
          assertEquals(10f, nodes[2].location.y)
      }

      @Test
      fun `history trims old nodes by maximum node count`() {
          val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 3, distanceWindow = 100f)
          for (i in 0..5) history.advance(Vector2f(i.toFloat(), 0f), facing = 0f, elapsed = i * 0.1f)

          assertEquals(3, history.nodes().size)
          assertEquals(3f, history.nodes().first().location.x)
      }

      @Test
      fun `history trims old nodes by distance window`() {
          val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 10, distanceWindow = 12f)
          history.advance(Vector2f(0f, 0f), facing = 0f, elapsed = 0f)
          history.advance(Vector2f(10f, 0f), facing = 0f, elapsed = 0.1f)
          history.advance(Vector2f(20f, 0f), facing = 0f, elapsed = 0.2f)

          val nodes = history.nodes()
          assertTrue(nodes.first().location.x >= 8f)
          assertEquals(20f, nodes.last().location.x)
      }

      @Test
      fun `history ignores repeated identical location`() {
          val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 10, distanceWindow = 100f)
          history.advance(Vector2f(3f, 4f), facing = 0f, elapsed = 0f)
          history.advance(Vector2f(3f, 4f), facing = 0f, elapsed = 0.1f)

          assertEquals(1, history.nodes().size)
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ASTDProjectileHistoryTest'`
- Expected output:
  ```text
  FAILED ASTDProjectileHistoryTest
  Unresolved reference: ASTDProjectileHistory
  ```

---

## Task 4: Implement true history sampler

**Step 1: Add history file**
- File: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileHistory.kt`
- Required behavior:
  - `data class ASTDProjectileHistoryNode(val location: Vector2f, val facing: Float, val elapsed: Float)`
  - `class ASTDProjectileHistory(minDistancePerNode: Float, maxHistoryNodes: Int, distanceWindow: Float)`
  - `advance(location: Vector2f, facing: Float, elapsed: Float)` stores copied positions only.
  - `nodes(): List<ASTDProjectileHistoryNode>` returns copied immutable snapshot.
  - `trimByDistanceWindow(maxDistance: Float)` removes old nodes beyond trailing distance from latest node.
  - `clear()` removes all nodes.

Implementation constraints:
- Use true input positions.
- Do not simulate projectile motion.
- Do not straighten non-linear paths.
- Do not use reflection.
- Do not use try-catch.

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ASTDProjectileHistoryTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 5: Add runtime lifecycle tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntimeTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.astd.renderer.projectile

  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  class ASTDProjectileVfxRuntimeTest {
      @Test
      fun `runtime state machine transitions from active to fading to removed`() {
          val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())

          assertEquals(ASTDProjectileVfxRuntimeState.Active, runtime.state)

          runtime.advanceForTests(locationX = 0f, locationY = 0f, facing = 0f, amount = 0.1f, projectileAlive = true)
          assertEquals(ASTDProjectileVfxRuntimeState.Active, runtime.state)
          assertTrue(runtime.historyNodesForTests().isNotEmpty())

          runtime.advanceForTests(locationX = 2f, locationY = 0f, facing = 0f, amount = 0.1f, projectileAlive = false)
          assertEquals(ASTDProjectileVfxRuntimeState.Fading, runtime.state)

          runtime.advanceForTests(locationX = 4f, locationY = 0f, facing = 0f, amount = 1f, projectileAlive = false)
          assertEquals(ASTDProjectileVfxRuntimeState.Removed, runtime.state)
      }

      @Test
      fun `removed runtime no longer samples history`() {
          val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())
          runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
          runtime.markProjectileGone()
          runtime.advanceForTests(1f, 0f, 0f, 1f, projectileAlive = false)
          val count = runtime.historyNodesForTests().size

          runtime.advanceForTests(20f, 0f, 0f, 1f, projectileAlive = true)

          assertEquals(count, runtime.historyNodesForTests().size)
      }

      @Test
      fun `runtime preserves non linear projectile history`() {
          val runtime = ASTDProjectileVfxRuntime.forTests(testPreset())
          runtime.advanceForTests(0f, 0f, 0f, 0.1f, projectileAlive = true)
          runtime.advanceForTests(10f, 0f, 0f, 0.1f, projectileAlive = true)
          runtime.advanceForTests(10f, 10f, 90f, 0.1f, projectileAlive = true)

          val nodes = runtime.historyNodesForTests()
          assertEquals(3, nodes.size)
          assertEquals(10f, nodes[1].location.x)
          assertEquals(0f, nodes[1].location.y)
          assertEquals(10f, nodes[2].location.x)
          assertEquals(10f, nodes[2].location.y)
      }

      private fun testPreset() = ASTDProjectileVfxPreset(
          id = "test_runtime",
          layers = listOf(ASTDProjectileVfxLayer.Trail("trail", 8f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(1f, 1f, 1f, 1f))),
          samplingPolicy = ASTDProjectileVfxSamplingPolicy(60f, 32, 1f, 0, 160f),
          fadePolicy = ASTDProjectileVfxFadePolicy(0f, 0.2f, 0.1f, 0.2f),
      )
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ASTDProjectileVfxRuntimeTest'`
- Expected output:
  ```text
  FAILED ASTDProjectileVfxRuntimeTest
  Unresolved reference: ASTDProjectileVfxRuntime
  ```

---

## Task 6: Implement runtime state machine

**Step 1: Add runtime file**
- File: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxRuntime.kt`
- Required behavior:
  - `enum class ASTDProjectileVfxRuntimeState { Active, Fading, Removed }`
  - `class ASTDProjectileVfxRuntime`
  - Holds `DamagingProjectileAPI?`, `ASTDProjectileVfxPreset`, `ASTDProjectileHistory`.
  - `advance(engine: CombatEngineAPI, amount: Float)` samples `projectile.location` while active.
  - `markProjectileGone()` transitions to `Fading`.
  - Fade timer transitions to `Removed`.
  - `dispose()` deletes BoxUtil entities through registered handles.
  - Test helpers may be `internal` and deterministic.

Implementation constraints:
- Runtime does not alter projectile properties.
- Runtime uses true projectile state only.
- Runtime does not create compatibility bridges.
- Avoid broad try-catch; entity delete errors may be handled through a small local helper only if necessary.

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ASTDProjectileVfxRuntimeTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 7: Add TrailEntity adapter tests

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxTrailEntitiesTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.astd.renderer.projectile

  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  class ASTDProjectileVfxTrailEntitiesTest {
      @Test
      fun `trail entity specs are derived for supported layer types`() {
          val history = ASTDProjectileHistory(minDistancePerNode = 1f, maxHistoryNodes = 16, distanceWindow = 120f)
          history.advance(org.lwjgl.util.vector.Vector2f(0f, 0f), 0f, 0f)
          history.advance(org.lwjgl.util.vector.Vector2f(10f, 0f), 0f, 0.1f)
          val layers = listOf(
              ASTDProjectileVfxLayer.Trail("trail", 8f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(1f, 1f, 1f, 1f)),
              ASTDProjectileVfxLayer.Glow("glow", 18f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(0.4f, 0.8f, 1f, 0.5f)),
              ASTDProjectileVfxLayer.Ribbon("ribbon", 4f, ASTDProjectileVfxLengthPolicy.Fixed(120f), ASTDColor(1f, 1f, 1f, 0.7f), frequency = 8f, amplitude = 6f),
              ASTDProjectileVfxLayer.HeadTrail("head", 10f, ASTDProjectileVfxLengthPolicy.LifetimeWindow(0.08f), ASTDColor(0.8f, 1f, 1f, 1f)),
          )

          val specs = ASTDProjectileVfxTrailEntities.buildSpecs(layers, history.nodes())

          assertEquals(4, specs.size)
          assertTrue(specs.all { it.nodes.size >= 2 })
          assertEquals("trail", specs[0].layerId)
          assertEquals("glow", specs[1].layerId)
          assertEquals("ribbon", specs[2].layerId)
          assertEquals("head", specs[3].layerId)
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ASTDProjectileVfxTrailEntitiesTest'`
- Expected output:
  ```text
  FAILED ASTDProjectileVfxTrailEntitiesTest
  Unresolved reference: ASTDProjectileVfxTrailEntities
  ```

---

## Task 8: Implement TrailEntity adapter layer

**Step 1: Add adapter file**
- File: `src/main/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxTrailEntities.kt`
- Required definitions:
  - `data class ASTDTrailEntitySpec`
  - `data class ASTDTrailLayerSpec`
  - `data class ASTDTrailRibbonDecorationSpec`
  - `sealed interface ASTDTrailEntityBuildResult`
  - `object ASTDProjectileVfxTrailEntities`
- Required behavior:
  - `buildSpecs(layers, historyNodes)` creates one TrailEntity spec for each supported layer.
  - `HeadTrail` uses the shortest tail window from recent history nodes.
  - `Ribbon` applies sinusoidal offset to a copied node list.
  - Add actual BoxUtil add method using `BoxUtilCombatVfx.ensureReady(engine)` and `BoxUtilCombatVfx.addEntity(...)`.
  - addEntity non-zero result deletes entity and returns structured failure.

Implementation constraints:
- Do not add vanilla particle fallback.
- Do not implement Sprite / Flare / instance particle.
- Do not use reflection.
- Avoid broad try-catch storm.

**Step 2: Run test and verify success**
- Command: `./gradlew test --tests '*ASTDProjectileVfxTrailEntitiesTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 9: Add frontend Kotlin export compatibility test

**Step 1: Write failing test**
- File: `src/test/kotlin/cn/kasuminova/astd/renderer/projectile/ASTDProjectileVfxExportCompatibilityTest.kt`
- Code:
  ```kotlin
  package cn.kasuminova.astd.renderer.projectile

  import kotlin.reflect.full.memberProperties
  import kotlin.test.Test
  import kotlin.test.assertNotNull
  import kotlin.test.assertTrue

  class ASTDProjectileVfxExportCompatibilityTest {
      @Test
      fun `runtime exports class names used by frontend kotlin export`() {
          val names = listOf(
              ASTDProjectileVfxPreset::class.simpleName,
              ASTDTrailEntitySpec::class.simpleName,
              ASTDTrailLayerSpec::class.simpleName,
              ASTDTrailRibbonDecorationSpec::class.simpleName,
              ASTDProjectileVfxFadePolicy::class.simpleName,
          ).filterNotNull()

          listOf(
              "ASTDProjectileVfxPreset",
              "ASTDTrailEntitySpec",
              "ASTDTrailLayerSpec",
              "ASTDTrailRibbonDecorationSpec",
              "ASTDProjectileVfxFadePolicy",
          ).forEach { expected ->
              assertTrue(names.contains(expected), "missing exported runtime type: $expected")
          }
      }

      @Test
      fun `runtime model fields exclude preview only fields`() {
          val propertyNames = ASTDProjectileVfxPreset::class.memberProperties.map { it.name }.toSet()
          listOf("timeline", "simulation", "previewCamera", "projectileVelocity", "curve", "loop").forEach { forbidden ->
              assertTrue(forbidden !in propertyNames, "preview-only field leaked into runtime preset: $forbidden")
          }
      }
  }
  ```

**Step 2: Run test and verify failure**
- Command: `./gradlew test --tests '*ASTDProjectileVfxExportCompatibilityTest'`
- Expected output:
  ```text
  FAILED ASTDProjectileVfxExportCompatibilityTest
  Unresolved reference: ASTDTrailEntitySpec
  ```

**Step 3: Adjust test to avoid kotlin-reflect dependency if needed**
- If `kotlin.reflect.full.memberProperties` is unavailable, replace field check with Java declared field names:
  ```kotlin
  val propertyNames = ASTDProjectileVfxPreset::class.java.declaredFields.map { it.name }.toSet()
  ```
- This test-only reflection is allowed for static compatibility inspection. Production code remains reflection-free.

---

## Task 10: Verify export compatibility

**Step 1: Run compatibility test**
- Command: `./gradlew test --tests '*ASTDProjectileVfxExportCompatibilityTest'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

---

## Task 11: Full backend verification

**Step 1: Run projectile runtime tests**
- Command: `./gradlew test --tests '*ASTDProjectileVfx*'`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 2: Run full build**
- Command: `./gradlew build`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 3: Run safe ss-csv generation**
- Command: `./gradlew :ss-csv:generateSsCsv`
- Expected output:
  ```text
  BUILD SUCCESSFUL
  ```

**Step 4: Risk search for new runtime package**
- Command: `grep -RInE 'Class\.forName|forName\(|getDeclared|java\.lang\.reflect|kotlin\.reflect|MethodHandles|ServiceLoader|ClassLoader|timeline|simulation|previewCamera|projectileVelocity|curve|loop|catch\s*\([^)]*(Throwable|Exception|any|unknown|e)\)|catch\s*\{' src/main/kotlin/cn/kasuminova/astd/renderer/projectile src/test/kotlin/cn/kasuminova/astd/renderer/projectile || true`
- Expected output:
  ```text
  ```

---

## Acceptance Criteria

- `renderer.projectile` 包存在完整 runtime 基础类型。
- projectile history 使用真实轨迹，支持导弹/追踪弹等非直线路径。
- 首版只使用 BoxUtil `TrailEntity`。
- 新 runtime 不替换旧体系。
- 没有前端专用字段进入游戏 runtime。
- 没有生产代码反射 / 动态 class lookup。
- 没有原版渲染 fallback。
- 没有 try-catch 风暴。
- `./gradlew build` 通过。
- `./gradlew :ss-csv:generateSsCsv` 通过。

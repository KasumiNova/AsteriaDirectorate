package cn.kasuminova.astd.impl.render

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin
import com.fs.starfarer.api.combat.CombatEngineAPI
import com.fs.starfarer.api.combat.CombatEngineLayers
import com.fs.starfarer.api.combat.ViewportAPI
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.Display
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * 贴图拖尾（+ bloom 管线弹头网格）的 GL 后端入口：每引擎一个渲染插件，组件经 [createHandle] 取得句柄后逐帧写顶点流。
 *
 * 复刻 MagicTrail 绘制语义：CPU 顶点流（世界系三角条带，u=带长弧长/平铺周期、v=±1 横向）经
 * STREAM_DRAW VBO 上传，片元着色器采样平铺贴图（X=横向 CLAMP、Y=带长向 REPEAT 滚动）乘顶点色，additive。
 * 无贴图快照（弹头三角网格，u=v=0）绑 1×1 白贴图走同一着色器，退化为纯顶点色。
 *
 * 光晕为真 bloom（BoxUtil 同款路线，非多遍拉宽——拉宽对高频闪电图案是重影不是发光）：
 * 全部快照先画进降采样离屏目标，分离高斯模糊乒乓两轮后，清晰遍 + 模糊遍 additive 合成。
 * 弹头并入同一管线是为了与拖尾能量同源：直绘弹头不进 bloom，接缝处能量差调色抹不平。
 */
object TexTrailRenderer {

    private const val ENGINE_KEY = "astd_tex_trail_renderer"

    private val log = Global.getLogger(TexTrailRenderer::class.java)

    /** 取引擎的渲染插件（未安装则安装）；无战斗引擎环境下返回 null。 */
    fun ensure(engine: CombatEngineAPI): Plugin? {
        engine.customData[ENGINE_KEY]?.let { return it as Plugin }
        val plugin = Plugin()
        engine.addLayeredRenderingPlugin(plugin)
        engine.customData[ENGINE_KEY] = plugin
        return plugin
    }

    /** 新建一枚拖尾句柄；插件不可用时返回 null（调用方按 attach 失败处理）。 */
    fun createHandle(engine: CombatEngineAPI): Handle? = ensure(engine)?.let { Handle(it) }

    /**
     * 一帧一次的绘制快照：顶点流为 [TEX_TRAIL_VERTEX_FLOATS] 交错布局的世界系几何。
     * [texturePath] 非空 = 贴图三角条带（拖尾）；为空 = 纯顶点色三角网格（bloom 管线弹头，绑 1×1 白贴图）。
     */
    class DrawSnapshot(
        val renderOrder: Int,
        val texturePath: String?,
        val vertices: FloatArray,
        val triangles: Boolean,
    )

    /** 组件侧句柄：线程安全地暂存最新顶点流，渲染线程取快照绘制。 */
    class Handle internal constructor(private val plugin: Plugin) {
        @Volatile
        internal var snapshot: DrawSnapshot? = null

        @Volatile
        var deleted: Boolean = false
            private set

        init {
            // 必须注册进插件句柄集，否则 render 遍历 handles 永远为空、拖尾整管线静默不绘制
            plugin.register(this)
        }

        fun update(renderOrder: Int, texturePath: String?, vertices: FloatArray, triangles: Boolean = false) {
            if (deleted) return
            snapshot = DrawSnapshot(renderOrder, texturePath, vertices, triangles)
        }

        fun delete() {
            if (deleted) return
            deleted = true
            snapshot = null
            plugin.remove(this)
        }
    }

    /** 每引擎渲染插件：持有句柄集合与共享 GL 程序，render 遍历时逐条拖尾一次 draw call。
     *  位于 ABOVE_PARTICLES_LOWER：同层插件绘制先后不确定，必须比网格层（ABOVE_PARTICLES 的 body/head 弹头）
     *  低一个引擎层，才能保证「拖尾垫底、弹头盖上」的构图不依赖注册顺序。 */
    class Plugin internal constructor() : BaseCombatLayeredRenderingPlugin(CombatEngineLayers.ABOVE_PARTICLES_LOWER) {

        private val handles = ConcurrentHashMap.newKeySet<Handle>()
        private var expired = false

        // 拖尾条带 GL 资源：懒创建于渲染线程
        private var programId = 0
        private var programFailed = false
        private var vaoId = 0
        private var vboId = 0
        private var locMvp = -1
        private var locTex = -1
        private var locEmissiveBoost = -1
        private val projBuffer: FloatBuffer = BufferUtils.createFloatBuffer(16)
        private val mvBuffer: FloatBuffer = BufferUtils.createFloatBuffer(16)
        private val mvpBuffer: FloatBuffer = BufferUtils.createFloatBuffer(16)
        private val viewportBuffer: IntBuffer = BufferUtils.createIntBuffer(16)

        // bloom 资源：全屏 quad 模糊/合成程序 + 半分辨率离屏目标（ping-pong）
        private var quadProgramId = 0
        private var quadVaoId = 0
        private var quadVboId = 0
        private var locQuadTex = -1
        private var locQuadDir = -1
        private var locQuadIntensity = -1
        private var bloomFboA = 0
        private var bloomFboB = 0
        private var bloomTexA = 0
        private var bloomTexB = 0
        private var bloomWidth = 0
        private var bloomHeight = 0

        /** 1×1 白贴图：无贴图快照（bloom 管线弹头网格）绑它，片元着色器退化为纯顶点色。 */
        private var whiteTexId = 0

        /**
         * 拖尾贴图自管缓存（路径 → texId，0=失败不再重试）。
         *
         * 必须自行解码上传，不能走游戏贴图系统：SSOptimizer LazyTextureManager 会把 ≥64KiB 的
         * `graphics/` 贴图做成「有 texId、无内容」的延迟对象，只有经它补丁过的绑定/取 id 路径才触发
         * 实际上传；本管线直接 `glBindTexture` 裸 id 绕过了补丁，采到空纹理 additive 下整条拖尾不可见
         * （zappy 64×256=64KiB 被延迟、twin 64×128 未被延迟，表现为一条可见一条不可见）。
         */
        private val trailTextures = HashMap<String, Int>()

        fun register(handle: Handle) {
            handles.add(handle)
        }

        fun remove(handle: Handle) {
            handles.remove(handle)
        }

        override fun getActiveLayers(): EnumSet<CombatEngineLayers> = EnumSet.of(CombatEngineLayers.ABOVE_PARTICLES_LOWER)

        override fun getRenderRadius(): Float = Float.MAX_VALUE

        override fun isExpired(): Boolean = expired

        override fun cleanup() {
            expired = true
            handles.clear()
            trailTextures.values.forEach { if (it > 0) GL11.glDeleteTextures(it) }
            trailTextures.clear()
            if (whiteTexId != 0) GL11.glDeleteTextures(whiteTexId)
            whiteTexId = 0
            deleteBloomTargets()
            if (quadVboId != 0) GL15.glDeleteBuffers(quadVboId)
            if (quadVaoId != 0) GL30.glDeleteVertexArrays(quadVaoId)
            if (quadProgramId != 0) GL20.glDeleteProgram(quadProgramId)
            if (vboId != 0) GL15.glDeleteBuffers(vboId)
            if (vaoId != 0) GL30.glDeleteVertexArrays(vaoId)
            if (programId != 0) GL20.glDeleteProgram(programId)
            quadVboId = 0; quadVaoId = 0; quadProgramId = 0
            vboId = 0; vaoId = 0; programId = 0
        }

        override fun render(layer: CombatEngineLayers, viewport: ViewportAPI) {
            if (expired || layer != CombatEngineLayers.ABOVE_PARTICLES_LOWER) return
            val snapshots = handles.mapNotNull { it.snapshot }.sortedBy { it.renderOrder }
            if (snapshots.isEmpty()) return

            val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
            val previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
            val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            val previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
            val previousDrawBuffer = GL11.glGetInteger(GL11.GL_DRAW_BUFFER)
            viewportBuffer.clear()
            GL11.glGetInteger(GL11.GL_VIEWPORT, viewportBuffer)
            GL11.glPushAttrib(GL11.GL_ENABLE_BIT or GL11.GL_COLOR_BUFFER_BIT or GL11.GL_TEXTURE_BIT)
            try {
                if (!ensureProgram()) return
                if (!ensureBloomTargets()) return
                GL11.glEnable(GL11.GL_TEXTURE_2D)
                GL11.glDisable(GL11.GL_CULL_FACE)
                GL11.glDisable(GL11.GL_DEPTH_TEST)
                GL11.glEnable(GL11.GL_BLEND)
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE)
                // 状态契约显式断言，不依赖入场值：颜色掩码/alpha 测试/模板测试任一被弄脏都会让
                // 本段绘制静默无效且零 GL 错误（与 glActiveTexture 同类的 GL 卫生盲区）
                GL11.glColorMask(true, true, true, true)
                GL11.glDisable(GL11.GL_ALPHA_TEST)
                GL11.glDisable(GL11.GL_STENCIL_TEST)

                // 1) 拖尾画进降采样离屏目标（bloom 提取源；emissive 增益只作用于此遍——
                // 拖尾 alpha 偏低，直接模糊会把能量摊薄到不可见，增益等价 BoxUtil 的 emissive 通道）
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFboA)
                val fboStatus = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
                if (fboStatus != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    // FBO 运行期衰减（驱动回收/上下文抖动）：warn 并重建，不静默带病渲染
                    log.warn("ASTD tex trail bloom fbo incomplete mid-flight: status=$fboStatus, recreating")
                    deleteBloomTargets()
                    if (!ensureBloomTargets()) return
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFboA)
                }
                // 帧缓冲的 draw buffer 是 FBO 自身状态，显式钉死，杜绝 GL_NONE 类静默无输出
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0)
                GL11.glViewport(0, 0, bloomWidth, bloomHeight)
                GL11.glClearColor(0f, 0f, 0f, 0f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
                drawSnapshots(snapshots, BLOOM_EMISSIVE_BOOST)

                // 2) 分离高斯模糊乒乓（两轮 H+V，光晕主体）。
                // 必须关 blend：乒乓目标不清屏，additive 会让模糊结果逐帧累加直至饱和成白斑
                GL11.glDisable(GL11.GL_BLEND)
                GL20.glUseProgram(quadProgramId)
                GL20.glUniform1i(locQuadTex, 0)
                GL20.glUniform1f(locQuadIntensity, 1f)
                GL30.glBindVertexArray(quadVaoId)
                repeat(BLOOM_BLUR_ITERATIONS) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFboB)
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTexA)
                    GL20.glUniform2f(locQuadDir, BLOOM_BLUR_STEP / bloomWidth, 0f)
                    GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, bloomFboA)
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTexB)
                    GL20.glUniform2f(locQuadDir, 0f, BLOOM_BLUR_STEP / bloomHeight)
                    GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
                }

                // 3) 回到默认帧缓冲：清晰遍 + bloom 合成
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo)
                GL11.glDrawBuffer(previousDrawBuffer)
                GL11.glViewport(viewportBuffer.get(0), viewportBuffer.get(1), viewportBuffer.get(2), viewportBuffer.get(3))
                GL11.glEnable(GL11.GL_BLEND)
                drawSnapshots(snapshots, 1f)
                GL20.glUseProgram(quadProgramId)
                GL20.glUniform2f(locQuadDir, 0f, 0f)
                GL20.glUniform1f(locQuadIntensity, BLOOM_INTENSITY)
                GL30.glBindVertexArray(quadVaoId)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, bloomTexA)
                GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
                GL30.glBindVertexArray(0)
                // GL_ARRAY_BUFFER 绑定不属于 VAO 状态也不被 glPushAttrib 保存，必须手动解绑——
                // 否则后续模组的 glVertexPointer(CPU 缓冲) 会被 LWJGL 以 "Cannot use Buffers when
                // Array Buffer Object is enabled" 直接崩游戏
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
            } finally {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo)
                GL11.glViewport(viewportBuffer.get(0), viewportBuffer.get(1), viewportBuffer.get(2), viewportBuffer.get(3))
                GL20.glUseProgram(previousProgram)
                // glActiveTexture 不被 glPushAttrib 保存，手动还原，避免污染游戏/其他模组的后续绘制
                GL13.glActiveTexture(previousActiveTexture)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture)
                GL11.glPopAttrib()
            }
        }

        /** 拖尾条带逐快照上传并绘制（当前绑定的程序/帧缓冲由调用方布置；条带程序与 VAO/VBO 在此布置）。 */
        private fun drawSnapshots(snapshots: List<DrawSnapshot>, emissiveBoost: Float) {
            GL20.glUseProgram(programId)
            uploadMvp()
            GL20.glUniformMatrix4(locMvp, false, mvpBuffer)
            GL13.glActiveTexture(GL13.GL_TEXTURE0)
            GL20.glUniform1i(locTex, 0)
            GL20.glUniform1f(locEmissiveBoost, emissiveBoost)
            GL30.glBindVertexArray(vaoId)
            // VAO 不跟踪 GL_ARRAY_BUFFER 绑定：上传前必须绑定自己的 VBO——否则 glBufferData 打到
            // buffer 0 上静默失败，VBO 永远停留在首帧内容（特效冻在出生点）
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId)
            for (snapshot in snapshots) {
                val vertexCount = snapshot.vertices.size / TEX_TRAIL_VERTEX_FLOATS
                if (vertexCount < 3) continue
                val textureId = snapshot.texturePath?.let { loadTrailTexture(it) } ?: whiteTexId
                if (textureId <= 0) continue
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId)
                val buffer = BufferUtils.createFloatBuffer(snapshot.vertices.size)
                buffer.put(snapshot.vertices).flip()
                // STREAM_DRAW 每帧整体孤儿化重传（单条约 1.5KB，开销可忽略）
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW)
                GL11.glDrawArrays(if (snapshot.triangles) GL11.GL_TRIANGLES else GL11.GL_TRIANGLE_STRIP, 0, vertexCount)
            }
        }

        /**
         * 解码并上传拖尾贴图（每路径一次，失败 warn 记 0 不重试）。
         * 自行 `glTexImage2D` 上传、自管 filter/wrap，完全不进游戏贴图系统（原因见 [trailTextures]）。
         */
        private fun loadTrailTexture(path: String): Int {
            trailTextures[path]?.let { return it }
            val id = try {
                val image = Global.getSettings().openStream(path).use { ImageIO.read(it) }
                    ?: throw java.io.IOException("ImageIO 无法解码 PNG: $path")
                uploadTrailTexture(image)
            } catch (t: Throwable) {
                log.warn("ASTD tex trail texture upload failed: $path", t)
                0
            }
            trailTextures[path] = id
            return id
        }

        /** 上传一张拖尾贴图并设参数：LINEAR 过滤，横向(S) CLAMP_TO_EDGE 防边缘渗出，带长向(T) REPEAT 供滚动平铺。 */
        private fun uploadTrailTexture(image: BufferedImage): Int {
            val id = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, image.width, image.height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, packRgba(image),
            )
            log.info("ASTD tex trail texture uploaded: ${image.width}x${image.height} texId=$id")
            return id
        }

        /** 四分之一分辨率 bloom 离屏目标：尺寸随显示变化重建，帧缓冲不完整则 warn 停用（bloom 缺席不阻断条带）。 */
        private fun ensureBloomTargets(): Boolean {
            val width = (Display.getWidth() / BLOOM_DOWNSCALE).coerceAtLeast(1)
            val height = (Display.getHeight() / BLOOM_DOWNSCALE).coerceAtLeast(1)
            if (bloomTexA != 0 && width == bloomWidth && height == bloomHeight) return true
            deleteBloomTargets()
            val texA = createTargetTexture(width, height)
            val texB = createTargetTexture(width, height)
            val fboA = createTargetFbo(texA)
            val fboB = createTargetFbo(texB)
            if (fboA == 0 || fboB == 0) {
                log.warn("ASTD tex trail bloom framebuffer incomplete, bloom disabled")
                if (texA != 0) GL11.glDeleteTextures(texA)
                if (texB != 0) GL11.glDeleteTextures(texB)
                if (fboA != 0) GL30.glDeleteFramebuffers(fboA)
                if (fboB != 0) GL30.glDeleteFramebuffers(fboB)
                return false
            }
            bloomTexA = texA; bloomTexB = texB; bloomFboA = fboA; bloomFboB = fboB
            bloomWidth = width; bloomHeight = height
            return true
        }

        private fun createTargetTexture(width: Int, height: Int): Int {
            val id = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
            return id
        }

        private fun createTargetFbo(textureId: Int): Int {
            val fbo = GL30.glGenFramebuffers()
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, textureId, 0)
            val complete = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0)
            return if (complete) fbo else 0
        }

        private fun deleteBloomTargets() {
            if (bloomTexA != 0) GL11.glDeleteTextures(bloomTexA)
            if (bloomTexB != 0) GL11.glDeleteTextures(bloomTexB)
            if (bloomFboA != 0) GL30.glDeleteFramebuffers(bloomFboA)
            if (bloomFboB != 0) GL30.glDeleteFramebuffers(bloomFboB)
            bloomTexA = 0; bloomTexB = 0; bloomFboA = 0; bloomFboB = 0
            bloomWidth = 0; bloomHeight = 0
        }

        /** 读取当前管线投影/模型视图矩阵，乘成 MVP 供顶点着色器把世界坐标变换到裁剪空间。 */
        private fun uploadMvp() {
            projBuffer.clear()
            mvBuffer.clear()
            mvpBuffer.clear()
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projBuffer)
            GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, mvBuffer)
            // 列主序 4x4：mvp = proj * mv
            for (col in 0 until 4) {
                for (row in 0 until 4) {
                    var sum = 0f
                    for (k in 0 until 4) {
                        sum += projBuffer.get(k * 4 + row) * mvBuffer.get(col * 4 + k)
                    }
                    mvpBuffer.put(col * 4 + row, sum)
                }
            }
        }

        private fun ensureProgram(): Boolean {
            if (programId != 0) return true
            // 编译失败不重试：失败一次即标记（warn 已在 compile/link 内打过），避免每帧刷屏告警
            if (programFailed) return false
            val program = linkProgram(VERTEX_SHADER, FRAGMENT_SHADER, arrayOf("inPos", "inUv", "inColor"))
            val quadProgram = linkProgram(QUAD_VERTEX_SHADER, QUAD_FRAGMENT_SHADER, arrayOf("inPos"))
            if (program == null || quadProgram == null) {
                if (program != null) GL20.glDeleteProgram(program)
                if (quadProgram != null) GL20.glDeleteProgram(quadProgram)
                programFailed = true
                return false
            }
            programId = program
            locMvp = GL20.glGetUniformLocation(program, "mvp")
            locTex = GL20.glGetUniformLocation(program, "tex")
            locEmissiveBoost = GL20.glGetUniformLocation(program, "emissiveBoost")

            vaoId = GL30.glGenVertexArrays()
            GL30.glBindVertexArray(vaoId)
            vboId = GL15.glGenBuffers()
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId)
            val stride = TEX_TRAIL_VERTEX_FLOATS * 4
            GL20.glEnableVertexAttribArray(0)
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, stride, 0L)
            GL20.glEnableVertexAttribArray(1)
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 8L)
            GL20.glEnableVertexAttribArray(2)
            GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, stride, 16L)
            GL30.glBindVertexArray(0)

            quadProgramId = quadProgram
            locQuadTex = GL20.glGetUniformLocation(quadProgram, "tex")
            locQuadDir = GL20.glGetUniformLocation(quadProgram, "dir")
            locQuadIntensity = GL20.glGetUniformLocation(quadProgram, "intensity")
            quadVaoId = GL30.glGenVertexArrays()
            GL30.glBindVertexArray(quadVaoId)
            quadVboId = GL15.glGenBuffers()
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVboId)
            val quad = BufferUtils.createFloatBuffer(8)
            quad.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).flip()
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW)
            GL20.glEnableVertexAttribArray(0)
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L)
            GL30.glBindVertexArray(0)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
            whiteTexId = GL11.glGenTextures()
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, whiteTexId)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            val white = BufferUtils.createByteBuffer(4)
            white.put(byteArrayOf(-1, -1, -1, -1)).flip()
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, white)
            return true
        }

        private fun linkProgram(vertexSource: String, fragmentSource: String, attribs: Array<String>): Int? {
            val vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource) ?: return null
            val fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource) ?: return null
            val program = GL20.glCreateProgram()
            GL20.glAttachShader(program, vertex)
            GL20.glAttachShader(program, fragment)
            attribs.forEachIndexed { index, name -> GL20.glBindAttribLocation(program, index, name) }
            GL20.glLinkProgram(program)
            GL20.glDeleteShader(vertex)
            GL20.glDeleteShader(fragment)
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                log.warn("ASTD tex trail link failed: ${GL20.glGetProgramInfoLog(program, 4096)}")
                GL20.glDeleteProgram(program)
                return null
            }
            return program
        }

        private fun compile(type: Int, source: String): Int? {
            val shader = GL20.glCreateShader(type)
            GL20.glShaderSource(shader, source)
            GL20.glCompileShader(shader)
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                log.warn("ASTD tex trail compile failed: ${GL20.glGetShaderInfoLog(shader, 4096)}")
                GL20.glDeleteShader(shader)
                return null
            }
            return shader
        }
    }
}

/** bloom 合成强度（模糊遍 additive 叠加到清晰遍之上的倍率）。 */
private const val BLOOM_INTENSITY = 1.6f

/** 离屏提取遍的 emissive 增益（只放大 alpha、封顶 1.0）：拖尾 alpha 偏低，不增益则模糊后光晕不可见。 */
private const val BLOOM_EMISSIVE_BOOST = 2.5f

/** bloom 离屏目标相对显示分辨率的降采样比（4 = 四分之一：同等核半径下光晕更宽，且模糊更省）。 */
private const val BLOOM_DOWNSCALE = 4

/** 模糊步长（离屏目标 texel 的倍数）：>1 放大高斯核有效半径，换更宽的光晕。 */
private const val BLOOM_BLUR_STEP = 2f

/** 分离高斯模糊乒乓轮数（每轮 = 水平 + 垂直各一遍）。 */
private const val BLOOM_BLUR_ITERATIONS = 2

private const val VERTEX_SHADER = """
#version 130
uniform mat4 mvp;
in vec2 inPos;
in vec2 inUv;
in vec4 inColor;
out vec2 uv;
out vec4 color;
void main() {
    gl_Position = mvp * vec4(inPos, 0.0, 1.0);
    uv = inUv;
    color = inColor;
}
"""

// NOTE: GLSL 源码内禁止非 ASCII 字符（含中文注释）——驱动按字节流解析，
// 多字节字符会让编译器提前报 "unexpected end of file"
private const val FRAGMENT_SHADER = """
#version 130
uniform sampler2D tex;
uniform float emissiveBoost;
in vec2 uv;
in vec4 color;
out vec4 fragColor;
void main() {
    // Texture layout (gr_trails_* / MagicTrail): X = across [0,1], Y = along trail (REPEAT scroll).
    // Vertex stream uv.x = arclen/tileLength - scroll, uv.y = across +-1 -> sample at (v*0.5+0.5, u).
    // Shape lives in texture alpha, RGB near white; tint by node color, alpha = texture.a * node.a.
    // emissiveBoost > 1 only on the offscreen bloom-extraction pass (sharp pass keeps 1.0).
    vec4 t = texture2D(tex, vec2(uv.y * 0.5 + 0.5, uv.x));
    float alpha = t.a * min(color.a * emissiveBoost, 1.0);
    if (alpha <= 0.004) discard;
    fragColor = vec4(color.rgb * t.rgb, alpha);
}
"""

private const val QUAD_VERTEX_SHADER = """
#version 130
in vec2 inPos;
out vec2 uv;
void main() {
    uv = inPos * 0.5 + 0.5;
    gl_Position = vec4(inPos, 0.0, 1.0);
}
"""

// 9-tap gaussian; dir=(0,0) 时退化为原样合成（bloom 叠加遍）
private const val QUAD_FRAGMENT_SHADER = """
#version 130
uniform sampler2D tex;
uniform vec2 dir;
uniform float intensity;
in vec2 uv;
out vec4 fragColor;
void main() {
    if (dir == vec2(0.0, 0.0)) {
        fragColor = texture2D(tex, uv) * intensity;
        return;
    }
    vec4 sum = texture2D(tex, uv) * 0.2270270270;
    sum += texture2D(tex, uv + dir * 1.3846153846) * 0.3162162162;
    sum += texture2D(tex, uv - dir * 1.3846153846) * 0.3162162162;
    sum += texture2D(tex, uv + dir * 3.2307692308) * 0.0702702703;
    sum += texture2D(tex, uv - dir * 3.2307692308) * 0.0702702703;
    fragColor = sum;
}
"""

/**
 * 把解码图像逐像素打包成 GL_RGBA/GL_UNSIGNED_BYTE 直连缓冲（纯函数，可测）。
 * 行宽 = width×4 恒为 4 字节对齐，默认 GL_UNPACK_ALIGNMENT=4 下无需额外处理。
 */
internal fun packRgba(image: BufferedImage): ByteBuffer {
    val buffer = BufferUtils.createByteBuffer(image.width * image.height * 4)
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            buffer.put(((argb ushr 16) and 0xFF).toByte())
            buffer.put(((argb ushr 8) and 0xFF).toByte())
            buffer.put((argb and 0xFF).toByte())
            buffer.put((argb ushr 24).toByte())
        }
    }
    buffer.flip()
    return buffer
}

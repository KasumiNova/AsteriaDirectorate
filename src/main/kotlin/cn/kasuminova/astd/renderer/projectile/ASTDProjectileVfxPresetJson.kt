package cn.kasuminova.astd.renderer.projectile

import org.json.JSONArray
import org.json.JSONObject

internal object ASTDProjectileVfxPresetJson {
    fun parse(json: JSONObject): ASTDProjectileVfxPreset {
        val lifecycle = parseLifecycle(json.optJSONObject("lifecycle"))
        return ASTDProjectileVfxPreset(
            id = json.getString("id"),
            layers = emptyList(),
            trailEntities = parseTrailEntities(json.optJSONArray("trailEntities")),
            headLayers = parseHeadLayers(json.optJSONArray("headLayers")),
            glowLayers = parseGlowLayers(json.optJSONArray("glowLayers")),
            mistLayers = parseMistLayers(json.optJSONArray("mistLayers")),
            sideWispLayers = parseSideWispLayers(json.optJSONArray("sideWispLayers")),
            ribbonDecorations = parseRibbonDecorations(json.optJSONArray("ribbonDecorations")),
            lifecycle = lifecycle,
            samplingPolicy = parseSamplingPolicy(json.optJSONObject("samplingPolicy")),
            fadePolicy = ASTDProjectileVfxFadePolicy(
                fadeInSeconds = lifecycleFadeInSeconds(json.optJSONObject("lifecycle")),
                fadeOutSeconds = lifecycleFadeOutSeconds(json.optJSONObject("lifecycle")),
                hitFadeOutSeconds = lifecycleFadeOutSeconds(json.optJSONObject("lifecycle")),
                expireFadeOutSeconds = lifecycleFadeOutSeconds(json.optJSONObject("lifecycle")),
            ),
        )
    }

    private fun parseTrailEntities(array: JSONArray?): List<ASTDTrailEntitySpec> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val entity = array.getJSONObject(index)
            val layer = parseTrailLayer(entity)
            ASTDTrailEntitySpec(
                layerId = entity.getString("id"),
                id = entity.getString("id"),
                nodes = emptyList(),
                layerSpec = layer,
                layers = listOf(layer),
                ribbonDecorations = parseRibbonDecorations(entity.optJSONArray("ribbonDecorations")),
                orientationMode = parseOrientationMode(entity.optString("orientationMode")),
                anchorMode = parseAnchorMode(entity.optString("anchorMode")),
            )
        }
    }

    private fun parseTrailLayer(json: JSONObject): ASTDTrailLayerSpec {
        val startColor = parseColor(json.getJSONArray("startColor"))
        return ASTDTrailLayerSpec(
            width = json.float("startWidth"),
            color = startColor,
            length = json.float("length"),
            diffuseSpritePath = json.getString("diffuseSpritePath"),
            emissiveSpritePath = json.getString("emissiveSpritePath"),
            startColor = startColor,
            endColor = parseColor(json.getJSONArray("endColor")),
            startEmissive = parseColor(json.getJSONArray("startEmissive")),
            endEmissive = parseColor(json.getJSONArray("endEmissive")),
            startWidth = json.float("startWidth"),
            endWidth = json.float("endWidth"),
            texturePixels = json.float("texturePixels"),
            textureSpeed = json.float("textureSpeed"),
            uvOffset = json.float("uvOffset"),
            fillStartAlpha = json.float("fillStartAlpha"),
            fillEndAlpha = json.float("fillEndAlpha"),
            fillStartFactor = json.float("fillStartFactor"),
            fillEndFactor = json.float("fillEndFactor"),
            jitterPower = json.float("jitterPower"),
            flick = json.getBoolean("flick"),
            syncFlick = json.getBoolean("syncFlick"),
            stripLineMode = json.getBoolean("stripLineMode"),
            flowWhenPaused = json.getBoolean("flowWhenPaused"),
            flickWhenPaused = json.getBoolean("flickWhenPaused"),
            flickMixValue = json.float("flickMixValue"),
            flickerSyncCode = json.getInt("flickerSyncCode"),
            blendMode = json.optString("blendMode", "additive"),
        )
    }

    private fun parseRibbonDecorations(array: JSONArray?): List<ASTDTrailRibbonDecorationSpec> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            ASTDTrailRibbonDecorationSpec(
                id = json.optString("id", ""),
                enabled = json.optBoolean("enabled", true),
                renderMode = json.optString("renderMode", "byLength"),
                startOffset = json.float("startOffset"),
                endOffset = json.float("endOffset"),
                thickness = json.float("thickness"),
                alphaScale = json.float("alphaScale"),
                lengthScale = json.float("lengthScale"),
                nodeCountScale = json.float("nodeCountScale"),
                amplitude = json.float("amplitude"),
                frequency = json.float("frequency"),
                waveSpeed = json.float("waveSpeed"),
                waveType = json.optString("waveType", "sine"),
                noiseScale = json.float("noiseScale"),
                blur = json.float("blur"),
                startColor = parseColor(json.getJSONArray("startColor")),
                endColor = parseColor(json.getJSONArray("endColor")),
                color = parseColor(json.getJSONArray("color")),
                colorGradient = parseRibbonGradient(json.optJSONObject("colorGradient")),
            )
        }
    }

    private fun parseRibbonGradient(json: JSONObject?): ASTDTrailDecorationColorGradientSpec {
        if (json == null) return ASTDTrailDecorationColorGradientSpec()
        val stops = json.optJSONArray("stops") ?: JSONArray()
        return ASTDTrailDecorationColorGradientSpec(
            enabled = json.optBoolean("enabled", false),
            stops = List(stops.length()) { index ->
                val stop = stops.getJSONObject(index)
                ASTDTrailDecorationColorStopSpec(
                    offset = stop.float("offset"),
                    color = parseColor(stop.getJSONArray("color")),
                )
            },
        )
    }

    private fun parseHeadLayers(array: JSONArray?): List<ASTDProjectileVfxHeadLayerSpec> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            ASTDProjectileVfxHeadLayerSpec(
                id = json.getString("id"),
                enabled = json.optBoolean("enabled", true),
                length = json.float("length"),
                width = json.float("width"),
                shoulderRatio = json.float("shoulderRatio"),
                rearRatio = json.float("rearRatio"),
                shellColorStart = parseColor(json.getJSONArray("shellColorStart")),
                shellColorMid = parseColor(json.getJSONArray("shellColorMid")),
                shellColorEnd = parseColor(json.getJSONArray("shellColorEnd")),
                blur = json.float("blur"),
                alphaScale = json.float("alphaScale"),
                blendMode = json.optString("blendMode", "additive"),
            )
        }
    }

    private fun parseGlowLayers(array: JSONArray?): List<ASTDProjectileVfxGlowLayerSpec> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            ASTDProjectileVfxGlowLayerSpec(
                id = json.getString("id"),
                enabled = json.optBoolean("enabled", true),
                widthScale = json.float("widthScale"),
                alphaScale = json.float("alphaScale"),
                blur = json.float("blur"),
                yOffset = json.float("yOffset"),
                colorMixTail = json.float("colorMixTail"),
                colorMixHead = json.float("colorMixHead"),
                gradientStops = parseColorStops(json.optJSONArray("gradientStops")),
            )
        }
    }

    private fun parseMistLayers(array: JSONArray?): List<ASTDProjectileVfxMistLayerSpec> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            ASTDProjectileVfxMistLayerSpec(
                id = json.getString("id"),
                enabled = json.optBoolean("enabled", true),
                blobCount = json.getInt("blobCount"),
                lengthScale = json.float("lengthScale"),
                widthScale = json.float("widthScale"),
                rxRange = parseFloatRange(json.getJSONObject("rxRange")),
                ryRange = parseFloatRange(json.getJSONObject("ryRange")),
                alphaRange = parseFloatRange(json.getJSONObject("alphaRange")),
                noiseScale = json.float("noiseScale"),
                driftSpeed = json.float("driftSpeed"),
                colorStart = parseColor(json.getJSONArray("colorStart")),
                colorEnd = parseColor(json.getJSONArray("colorEnd")),
            )
        }
    }

    private fun parseSideWispLayers(array: JSONArray?): List<ASTDProjectileVfxSideWispLayerSpec> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            ASTDProjectileVfxSideWispLayerSpec(
                id = json.getString("id"),
                enabled = json.optBoolean("enabled", true),
                offsets = parseFloatList(json.getJSONArray("offsets")),
                widthScale = json.float("widthScale"),
                alphaScale = json.float("alphaScale"),
                blur = json.float("blur"),
                lengthStartRatio = json.float("lengthStartRatio"),
                lengthEndRatio = json.float("lengthEndRatio"),
                color = parseColor(json.getJSONArray("color")),
            )
        }
    }

    private fun parseLifecycle(json: JSONObject?): ASTDProjectileVfxLifecycleSpec {
        if (json == null) return ASTDProjectileVfxLifecycleSpec()
        return ASTDProjectileVfxLifecycleSpec(
            durationSeconds = json.float("durationSeconds"),
            flightEndRatio = json.float("flightEndRatio"),
            dissolveStartRatio = json.float("dissolveStartRatio"),
            preDissolveFraction = json.float("preDissolveFraction"),
            projectileHeadSizeScale = json.float("projectileHeadSizeScale"),
            historySampleMultiplier = json.float("historySampleMultiplier"),
            historySmoothingPasses = json.getInt("historySmoothingPasses"),
            ribbonWaveSoftening = json.float("ribbonWaveSoftening"),
            layoutReferenceWidth = json.optDouble("layoutReferenceWidth", 1280.0).toFloat(),
        )
    }

    private fun parseSamplingPolicy(json: JSONObject?): ASTDProjectileVfxSamplingPolicy {
        requireNotNull(json) { "samplingPolicy is required" }
        return ASTDProjectileVfxSamplingPolicy(
            historyFps = json.float("historyFps"),
            maxHistoryNodes = json.getInt("maxHistoryNodes"),
            minDistancePerNode = json.float("minDistancePerNode"),
            smoothingPasses = json.getInt("smoothingPasses"),
            distanceWindow = json.float("distanceWindow"),
        )
    }

    private fun parseColorStops(array: JSONArray?): List<ASTDColorStopSpec> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val json = array.getJSONObject(index)
            ASTDColorStopSpec(
                offset = json.float("offset"),
                color = parseColor(json.getJSONArray("color")),
            )
        }
    }

    private fun parseFloatRange(json: JSONObject): ASTDFloatRangeSpec {
        return ASTDFloatRangeSpec(
            min = json.float("min"),
            max = json.float("max"),
        )
    }

    private fun parseFloatList(array: JSONArray): List<Float> {
        return List(array.length()) { index -> array.getDouble(index).toFloat() }
    }

    private fun parseColor(array: JSONArray): ASTDColor {
        require(array.length() == 4) { "color arrays must contain exactly 4 values" }
        return ASTDColor(
            red = array.getDouble(0).toFloat(),
            green = array.getDouble(1).toFloat(),
            blue = array.getDouble(2).toFloat(),
            alpha = array.getDouble(3).toFloat(),
        )
    }

    private fun parseOrientationMode(value: String): ASTDProjectileVfxOrientationMode = when (value) {
        "projectileFacing" -> ASTDProjectileVfxOrientationMode.ProjectileFacing
        "custom" -> ASTDProjectileVfxOrientationMode.Custom
        else -> ASTDProjectileVfxOrientationMode.ProjectileVelocity
    }

    private fun parseAnchorMode(value: String): ASTDProjectileVfxAnchorMode = when (value) {
        "headLocked", "" -> ASTDProjectileVfxAnchorMode.HeadLocked
        else -> ASTDProjectileVfxAnchorMode.HeadLocked
    }

    private fun lifecycleFadeInSeconds(json: JSONObject?): Float = json?.optDouble("fadeInSeconds", 0.0)?.toFloat() ?: 0f

    private fun lifecycleFadeOutSeconds(json: JSONObject?): Float = json?.optDouble("fadeOutSeconds", 0.15)?.toFloat() ?: 0.15f

    private fun JSONObject.float(key: String): Float = getDouble(key).toFloat()
}

package cn.kasuminova.astd.renderer.projectile

import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentRegistry
import cn.kasuminova.astd.renderer.projectile.component.ASTDProjectileVfxComponentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ASTDProjectileVfxComponentRegistryTest {
    @Test
    fun `component order is preserved when building render layers`() {
        val layers = ASTDProjectileVfxComponentRegistry.layersFor(
            components = listOf(
                trail(),
                ASTDProjectileVfxComponentSpec.Glow("glow", trailId = "trail", layers = listOf(glowLayer())),
                ASTDProjectileVfxComponentSpec.Body("body", trailId = "trail"),
                ASTDProjectileVfxComponentSpec.Ribbon("ribbon", trailId = "trail", ribbons = listOf(ribbon())),
            ),
            lifecycle = ASTDProjectileVfxLifecycleSpec(),
        )

        assertEquals(
            listOf(
                "ASTDProjectileVfxGlowRenderLayer",
                "ASTDProjectileVfxBodyRenderLayer",
                "ASTDProjectileVfxRibbonRenderLayer",
            ),
            layers.map { it.javaClass.simpleName },
        )
    }

    @Test
    fun `component references to missing trail throw`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ASTDProjectileVfxComponentRegistry.layersFor(
                components = listOf(ASTDProjectileVfxComponentSpec.Body("body", trailId = "missing")),
                lifecycle = ASTDProjectileVfxLifecycleSpec(),
            )
        }

        assertEquals("Projectile VFX component references missing trailId=missing", error.message)
    }

    @Test
    fun `disabled components do not instantiate render layers`() {
        val layers = ASTDProjectileVfxComponentRegistry.layersFor(
            components = listOf(
                trail(),
                ASTDProjectileVfxComponentSpec.Body("body", trailId = "trail", enabled = false),
                ASTDProjectileVfxComponentSpec.Glow("glow", trailId = "trail", layers = listOf(glowLayer()), enabled = false),
            ),
            lifecycle = ASTDProjectileVfxLifecycleSpec(),
        )

        assertEquals(listOf("ASTDProjectileVfxTrailRenderLayer"), layers.map { it.javaClass.simpleName })
    }

    @Test
    fun `unregistered extra component throws`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ASTDProjectileVfxComponentRegistry.layersFor(
                components = listOf(trail(), ASTDProjectileVfxComponentSpec.Extra("draft", type = "draft")),
                lifecycle = ASTDProjectileVfxLifecycleSpec(),
            )
        }

        assertEquals("Projectile VFX component extra type is not registered: id=draft type=draft", error.message)
    }

    private fun trail() = ASTDProjectileVfxComponentSpec.Trail(
        id = "trail",
        layer = ASTDTrailLayerSpec(width = 8f, color = ASTDColor(1f, 1f, 1f, 1f), length = 120f),
    )

    private fun glowLayer() = ASTDProjectileVfxGlowLayerSpec(
        id = "glow_0",
        widthScale = 2f,
        alphaScale = 0.5f,
        blur = 8f,
        yOffset = 0f,
        colorMixTail = 0.5f,
        colorMixHead = 1f,
    )

    private fun ribbon() = ASTDTrailRibbonDecorationSpec(
        id = "ribbon_0",
        frequency = 1f,
        amplitude = 1f,
    )
}

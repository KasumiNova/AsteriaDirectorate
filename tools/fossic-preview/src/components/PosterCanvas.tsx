import { useEffect, useRef } from 'react'
import { renderPoster } from '../render'
import { layoutSpecs } from '../render/shared'
import type { ImageAssets, PosterSettings } from '../render/shared'

interface PosterCanvasProps {
  settings: PosterSettings
  assets: ImageAssets
  fontsReady: boolean
  playing: boolean
  scale: number
  onRenderError: (message: string) => void
}

const labels = {
  hero: '横版舰船主视觉预览',
  atlasArc: '星坠设计七艘舰船技术图版预览',
  atlasLens: '紫菀设计六艘舰船技术图版预览',
  banner: '循环动画横幅预览',
}

export default function PosterCanvas({ settings, assets, fontsReady, playing, scale, onRenderError }: PosterCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const timeRef = useRef(0)
  const spec = layoutSpecs[settings.layout]

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas || !fontsReady) return
    let frame = 0
    let active = true
    const start = performance.now() - timeRef.current * 1000
    const draw = (time: number) => {
      if (!active) return
      try {
        timeRef.current = time
        renderPoster(canvas, settings, assets, scale, time)
        onRenderError('')
      } catch (error) {
        console.error(error)
        onRenderError(error instanceof Error ? error.message : String(error))
        active = false
      }
    }
    if (settings.layout === 'banner' && playing) {
      const tick = (now: number) => {
        draw((now - start) / 1000)
        if (active) frame = requestAnimationFrame(tick)
      }
      frame = requestAnimationFrame(tick)
    } else {
      draw(settings.layout === 'banner' ? timeRef.current : 0)
    }
    return () => {
      active = false
      cancelAnimationFrame(frame)
    }
  }, [assets, fontsReady, onRenderError, playing, scale, settings])

  return (
    <canvas
      ref={canvasRef}
      className={`poster-canvas poster-canvas--${settings.layout}`}
      width={spec.width * scale}
      height={spec.height * scale}
      role="img"
      aria-label={labels[settings.layout]}
    />
  )
}

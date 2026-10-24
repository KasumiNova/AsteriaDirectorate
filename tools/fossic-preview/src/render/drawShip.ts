import type { Ship } from '../data/ships'
import { shipAsset } from './shared'
import type { ImageAssets } from './shared'

export interface DrawShipOptions {
  x: number
  y: number
  width: number
  rotation?: number
  bloom?: number
  halo?: number
}

/** 以中心点绘制舰船，主贴图与发光层保持相同尺寸和变换。 */
export function drawShip(
  ctx: CanvasRenderingContext2D,
  ship: Ship,
  assets: ImageAssets,
  options: DrawShipOptions,
): boolean {
  const sprite = assets.get(shipAsset(ship))
  const bloom = assets.get(shipAsset(ship, true))
  if (!sprite || !bloom) return false

  const height = options.width * (sprite.naturalHeight / sprite.naturalWidth)
  const intensity = options.bloom ?? 1.1
  const halo = options.halo ?? 0.14
  ctx.save()
  ctx.translate(options.x, options.y)
  ctx.rotate(((options.rotation ?? 0) * Math.PI) / 180)
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(sprite, -options.width / 2, -height / 2, options.width, height)
  ctx.globalCompositeOperation = 'lighter'
  if (halo > 0 && intensity > 0) {
    ctx.save()
    ctx.globalAlpha = Math.min(1, intensity * halo)
    const outputScale = Math.hypot(ctx.getTransform().a, ctx.getTransform().b)
    ctx.filter = `blur(${Math.max(2, options.width * 0.018) * outputScale}px)`
    ctx.drawImage(bloom, -options.width / 2, -height / 2, options.width, height)
    ctx.restore()
  }
  ctx.globalAlpha = Math.min(1, intensity)
  if (intensity > 1) {
    ctx.drawImage(bloom, -options.width / 2, -height / 2, options.width, height)
    ctx.globalAlpha = intensity - 1
  }
  ctx.drawImage(bloom, -options.width / 2, -height / 2, options.width, height)
  ctx.restore()
  return true
}

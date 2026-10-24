import { drawAtlas } from './atlas'
import { drawBanner } from './banner'
import { drawHero } from './hero'
import { layoutSpecs } from './shared'
import type { ImageAssets, PosterSettings } from './shared'

/** 将海报绘制到指定画布，导出倍率仅改变 backing store，不改变版式坐标。 */
export function renderPoster(
  canvas: HTMLCanvasElement,
  settings: PosterSettings,
  assets: ImageAssets,
  scale = 1,
  timeSeconds = 0,
): void {
  const spec = layoutSpecs[settings.layout]
  const pixelWidth = spec.width * scale
  const pixelHeight = spec.height * scale
  if (canvas.width !== pixelWidth) canvas.width = pixelWidth
  if (canvas.height !== pixelHeight) canvas.height = pixelHeight
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('当前浏览器无法创建 Canvas 2D 上下文')
  ctx.setTransform(scale, 0, 0, scale, 0, 0)
  ctx.clearRect(0, 0, spec.width, spec.height)
  ctx.textBaseline = 'alphabetic'
  if (settings.layout === 'hero') drawHero(ctx, assets, settings)
  else if (settings.layout === 'atlasArc') drawAtlas(ctx, assets, settings, 'ARC')
  else if (settings.layout === 'atlasLens') drawAtlas(ctx, assets, settings, 'LENS')
  else drawBanner(ctx, assets, settings, timeSeconds)
}

import type { ImageAssets, PosterSettings } from './shared'
import { colors, drawCover, drawSafeArea, drawTrackedText, setFont } from './shared'

const width = 1920
const height = 360
export const BANNER_DURATION_SECONDS = 4
export const BANNER_DEFAULT_FRAME_RATE = 12

function setFittedFont(
  ctx: CanvasRenderingContext2D,
  text: string,
  size: number,
  maxWidth: number,
  weight = 500,
  tracking = 0,
): number {
  setFont(ctx, size, weight, true)
  const textWidth = ctx.measureText(text).width + Math.max(0, Array.from(text).length - 1) * tracking
  if (textWidth <= maxWidth) return tracking
  const ratio = maxWidth / textWidth
  setFont(ctx, size * ratio, weight, true)
  return tracking * ratio
}

function polygon(ctx: CanvasRenderingContext2D, points: number[][]): void {
  ctx.beginPath()
  points.forEach(([x, y], index) => {
    if (index === 0) ctx.moveTo(x, y)
    else ctx.lineTo(x, y)
  })
  ctx.closePath()
  ctx.fill()
}

function drawStripes(ctx: CanvasRenderingContext2D, phase: number): void {
  ctx.save()
  ctx.beginPath()
  ctx.rect(1390, 268, 430, 34)
  ctx.clip()
  ctx.strokeStyle = 'rgba(200, 170, 238, 0.2)'
  ctx.lineWidth = 2
  const offset = phase * 48
  for (let x = 1320 + offset; x < 1900; x += 48) {
    ctx.beginPath()
    ctx.moveTo(x, 310)
    ctx.lineTo(x + 70, 260)
    ctx.stroke()
  }
  ctx.restore()
}

function drawArrows(ctx: CanvasRenderingContext2D, phase: number): void {
  ctx.save()
  ctx.strokeStyle = colors.arc
  ctx.lineWidth = 2
  for (let index = 0; index < 3; index++) {
    const x = 1170 + index * 42
    ctx.globalAlpha = 0.12 + 0.07 * Math.sin(phase * Math.PI * 2 - index * 0.8)
    ctx.beginPath()
    ctx.moveTo(x, 149)
    ctx.lineTo(x + 18, 180)
    ctx.lineTo(x, 211)
    ctx.stroke()
  }
  ctx.restore()
}

function drawEdgeDetails(ctx: CanvasRenderingContext2D, phase: number): void {
  ctx.save()
  ctx.strokeStyle = colors.arc
  ctx.fillStyle = colors.arc
  ctx.lineWidth = 1
  ctx.globalAlpha = 0.2
  for (const y of [24, 336]) {
    ctx.beginPath()
    ctx.moveTo(80, y)
    ctx.lineTo(1824, y)
    for (let x = 80; x <= 1824; x += 32) {
      const length = (x - 80) % 128 === 0 ? 8 : 3
      ctx.moveTo(x, y)
      ctx.lineTo(x, y + (y < height / 2 ? length : -length))
    }
    ctx.stroke()
  }
  ctx.beginPath()
  ctx.rect(1116, 56, 240, 248)
  ctx.clip()
  ctx.globalAlpha = 0.1
  for (const radius of [88, 108]) {
    ctx.beginPath()
    ctx.arc(1234, 180, radius, -1.3, 1.3)
    ctx.stroke()
    ctx.beginPath()
    ctx.arc(1234, 180, radius, Math.PI - 1.3, Math.PI + 1.3)
    ctx.stroke()
  }
  const angle = phase * Math.PI * 2
  ctx.globalAlpha = 0.38
  ctx.fillRect(1234 + Math.cos(angle) * 108 - 2, 180 + Math.sin(angle) * 108 - 2, 4, 4)
  ctx.restore()
}

/** 按四秒周期绘制横幅，时间单位为秒。 */
export function drawBanner(
  ctx: CanvasRenderingContext2D,
  assets: ImageAssets,
  settings: PosterSettings,
  timeSeconds: number,
): void {
  const phase = ((timeSeconds % BANNER_DURATION_SECONDS) + BANNER_DURATION_SECONDS) % BANNER_DURATION_SECONDS / BANNER_DURATION_SECONDS
  ctx.fillStyle = '#080b0e'
  ctx.fillRect(0, 0, width, height)
  const background = assets.get('/assets/backgrounds/deep-space-hero.png')
  if (background) {
    ctx.save()
    ctx.globalAlpha = 0.13 * settings.backgroundBrightness / 100
    drawCover(ctx, background, width, height)
    ctx.restore()
  }

  ctx.fillStyle = 'rgba(5, 8, 11, 0.88)'
  polygon(ctx, [[80, 48], [1120, 48], [1200, 180], [1120, 312], [80, 312], [26, 180]])
  ctx.fillStyle = 'rgba(8, 11, 15, 0.92)'
  polygon(ctx, [[1328, 0], [1920, 0], [1920, 360], [1210, 360], [1280, 260]])

  if (settings.showGrid) {
    ctx.strokeStyle = 'rgba(126, 189, 194, 0.09)'
    ctx.lineWidth = 1
    for (let x = 120; x < width; x += 120) {
      ctx.beginPath()
      ctx.moveTo(x + 0.5, 0)
      ctx.lineTo(x + 0.5, height)
      ctx.stroke()
    }
  }

  drawEdgeDetails(ctx, phase)
  drawArrows(ctx, phase)
  drawStripes(ctx, phase)
  ctx.fillStyle = colors.arc
  ctx.fillRect(80, 47, 190, 3)
  ctx.fillStyle = colors.lens
  ctx.fillRect(1690, 48, 130, 3)
  ctx.strokeStyle = 'rgba(137, 208, 213, 0.38)'
  ctx.lineWidth = 1
  ctx.beginPath()
  ctx.moveTo(80, 311.5)
  ctx.lineTo(1110, 311.5)
  ctx.lineTo(1180, 180)
  ctx.stroke()

  ctx.fillStyle = colors.arc
  const kicker = settings.bannerKicker.toUpperCase()
  const kickerTracking = setFittedFont(ctx, kicker, 32, 960, 500, 4)
  drawTrackedText(ctx, kicker, 100, 96, kickerTracking)
  ctx.fillStyle = colors.paper
  setFittedFont(ctx, settings.bannerTitle, 144, 1000)
  ctx.fillText(settings.bannerTitle, 96, 250)

  ctx.textAlign = 'right'
  ctx.fillStyle = colors.paper
  setFittedFont(ctx, settings.bannerSide, 76, 470)
  ctx.fillText(settings.bannerSide, 1818, 222)
  ctx.fillStyle = colors.lens
  ctx.fillRect(1818, 246, 8, 8)
  ctx.textAlign = 'left'

  if (settings.showAnnotations) {
    ctx.strokeStyle = 'rgba(163, 205, 207, 0.4)'
    ;[[48, 48], [1872, 48], [48, 312], [1872, 312]].forEach(([x, y]) => {
      ctx.beginPath()
      ctx.moveTo(x - 10, y)
      ctx.lineTo(x + 10, y)
      ctx.moveTo(x, y - 10)
      ctx.lineTo(x, y + 10)
      ctx.stroke()
    })
  }
  if (settings.showSafeArea) drawSafeArea(ctx, width, height, 36)
}

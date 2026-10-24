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
  ctx.strokeStyle = 'rgba(200, 170, 238, 0.38)'
  ctx.lineWidth = 5
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
  const offset = phase * 240
  for (let x = -300 + offset; x < 1550; x += 240) {
    ctx.fillStyle = 'rgba(116, 220, 232, 0.075)'
    polygon(ctx, [[x, 58], [x + 82, 58], [x + 142, 180], [x + 82, 302], [x, 302], [x + 60, 180]])
    ctx.fillStyle = 'rgba(116, 220, 232, 0.12)'
    polygon(ctx, [[x + 26, 156], [x + 67, 156], [x + 91, 180], [x + 67, 204], [x + 26, 204], [x + 50, 180]])
  }
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

  ctx.fillStyle = 'rgba(232, 236, 242, 0.035)'
  setFont(ctx, 476, 600, true)
  ctx.fillText(settings.bannerKicker.toUpperCase(), -20, 340)

  drawArrows(ctx, phase)
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

  const scanX = -500 + phase * 3000
  const scan = ctx.createLinearGradient(scanX - 180, 0, scanX + 180, 0)
  scan.addColorStop(0, 'rgba(109, 224, 232, 0)')
  scan.addColorStop(0.5, 'rgba(142, 231, 235, 0.12)')
  scan.addColorStop(1, 'rgba(109, 224, 232, 0)')
  ctx.fillStyle = scan
  polygon(ctx, [[scanX - 150, 0], [scanX + 80, 0], [scanX - 20, 360], [scanX - 250, 360]])

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
  ctx.save()
  ctx.globalAlpha = 0.9 + Math.sin(phase * Math.PI * 2) * 0.1
  ctx.fillStyle = colors.paper
  setFittedFont(ctx, settings.bannerTitle, 144, 1000)
  ctx.fillText(settings.bannerTitle, 96, 250)
  ctx.restore()

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

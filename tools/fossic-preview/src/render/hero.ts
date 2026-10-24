import { shipById } from '../data/ships'
import { drawShip } from './drawShip'
import type { ImageAssets, PosterSettings } from './shared'
import {
  colors,
  drawCornerFrame,
  drawCover,
  drawSafeArea,
  drawTrackedText,
  fontMono,
  setFont,
} from './shared'

const width = 2560
const height = 1440

function drawBackground(ctx: CanvasRenderingContext2D, assets: ImageAssets, settings: PosterSettings): void {
  ctx.fillStyle = '#06090c'
  ctx.fillRect(0, 0, width, height)
  const background = assets.get('/assets/backgrounds/deep-space-hero.png')
  if (background) drawCover(ctx, background, width, height)

  const brightness = settings.backgroundBrightness / 100
  ctx.fillStyle = brightness <= 1
    ? `rgba(2, 5, 8, ${1 - brightness})`
    : `rgba(151, 174, 181, ${(brightness - 1) * 0.22})`
  ctx.fillRect(0, 0, width, height)

  const leftShade = ctx.createLinearGradient(0, 0, 1550, 0)
  leftShade.addColorStop(0, 'rgba(3, 7, 10, 0.88)')
  leftShade.addColorStop(0.48, 'rgba(3, 7, 10, 0.5)')
  leftShade.addColorStop(1, 'rgba(3, 7, 10, 0)')
  ctx.fillStyle = leftShade
  ctx.fillRect(0, 0, width, height)

  const vignette = ctx.createRadialGradient(1370, 690, 260, 1280, 720, 1560)
  vignette.addColorStop(0, 'rgba(0, 0, 0, 0)')
  vignette.addColorStop(0.72, 'rgba(0, 0, 0, 0.14)')
  vignette.addColorStop(1, 'rgba(0, 0, 0, 0.68)')
  ctx.fillStyle = vignette
  ctx.fillRect(0, 0, width, height)
}

function drawGrid(ctx: CanvasRenderingContext2D): void {
  ctx.save()
  ctx.strokeStyle = 'rgba(151, 180, 178, 0.055)'
  ctx.lineWidth = 1
  for (let x = 64; x < width; x += 128) {
    ctx.beginPath()
    ctx.moveTo(x + 0.5, 0)
    ctx.lineTo(x + 0.5, height)
    ctx.stroke()
  }
  for (let y = 80; y < height; y += 128) {
    ctx.beginPath()
    ctx.moveTo(0, y + 0.5)
    ctx.lineTo(width, y + 0.5)
    ctx.stroke()
  }
  ctx.strokeStyle = 'rgba(166, 197, 191, 0.14)'
  for (let x = 128; x < width; x += 256) {
    for (let y = 144; y < height; y += 256) {
      ctx.beginPath()
      ctx.moveTo(x - 5, y)
      ctx.lineTo(x + 5, y)
      ctx.moveTo(x, y - 5)
      ctx.lineTo(x, y + 5)
      ctx.stroke()
    }
  }
  ctx.restore()
}

function drawBrand(ctx: CanvasRenderingContext2D, assets: ImageAssets): void {
  const crest = assets.get('/assets/crest.png')
  if (crest) ctx.drawImage(crest, 142, 100, 76, 76)
  ctx.fillStyle = colors.paper
  setFont(ctx, 26, 600, true)
  drawTrackedText(ctx, 'ASTERIA DIRECTORATE', 244, 133, 2.4)
  ctx.fillStyle = colors.muted
  setFont(ctx, 21, 400)
  drawTrackedText(ctx, '菀星设计局', 245, 169, 7)

  ctx.textAlign = 'right'
  ctx.fillStyle = colors.muted
  setFont(ctx, 18, 500, true)
  ctx.fillText('ORIGINAL SHIPSET', 2408, 122)
  ctx.fillStyle = colors.faint
  setFont(ctx, 16, 400, true)
  ctx.fillText('SECTOR MOD  /  FOSSIC ARCHIVE', 2408, 154)
  ctx.textAlign = 'left'
  ctx.strokeStyle = 'rgba(182, 204, 197, 0.22)'
  ctx.beginPath()
  ctx.moveTo(144, 208.5)
  ctx.lineTo(2410, 208.5)
  ctx.stroke()
}

function drawEditorial(ctx: CanvasRenderingContext2D): void {
  ctx.fillStyle = colors.accent
  setFont(ctx, 17, 500, true)
  drawTrackedText(ctx, 'A S T E R I A   /   0 1', 150, 330, 1)

  ctx.fillStyle = colors.paper
  setFont(ctx, 88, 500)
  ctx.fillText('群星之下，', 142, 452)
  ctx.fillText('同频共振。', 142, 562)

  ctx.fillStyle = colors.muted
  setFont(ctx, 23, 400)
  ctx.fillText('跨越寂静星海的舰船设计档案', 150, 626)
  ctx.fillStyle = colors.faint
  setFont(ctx, 16, 400, true)
  ctx.fillText('THIRTEEN HULLS. TWO DESIGN LANGUAGES.', 151, 664)

  const features = [
    ['01', '全息舰装', 'HOLOGRAPHIC ARMATURE'],
    ['02', '智能核心', 'INTELLIGENT CORE'],
    ['03', '双系舰体', 'DUAL HULL SYSTEM'],
  ]
  features.forEach(([index, title, subtitle], itemIndex) => {
    const x = 150 + itemIndex * 266
    ctx.strokeStyle = 'rgba(170, 195, 188, 0.3)'
    ctx.beginPath()
    ctx.moveTo(x, 1092.5)
    ctx.lineTo(x + 218, 1092.5)
    ctx.stroke()
    ctx.fillStyle = colors.faint
    setFont(ctx, 15, 500, true)
    ctx.fillText(index, x, 1126)
    ctx.fillStyle = colors.paper
    setFont(ctx, 24, 500)
    ctx.fillText(title, x, 1170)
    ctx.fillStyle = colors.faint
    setFont(ctx, 12, 400, true)
    ctx.fillText(subtitle, x, 1200)
  })

  ctx.strokeStyle = 'rgba(182, 204, 197, 0.22)'
  ctx.beginPath()
  ctx.moveTo(144, 1282.5)
  ctx.lineTo(2410, 1282.5)
  ctx.stroke()
  ctx.fillStyle = colors.muted
  setFont(ctx, 15, 400, true)
  ctx.fillText('ASTERIA DIRECTORATE  /  ORIGINAL SHIPSET', 150, 1324)
  ctx.textAlign = 'right'
  ctx.fillText('ARCHIVE 01   ·   ARC / LENS   ·   SECTOR ARCHIVE', 2408, 1324)
  ctx.textAlign = 'left'
}

function drawFormationMarks(ctx: CanvasRenderingContext2D): void {
  ctx.save()
  ctx.strokeStyle = 'rgba(149, 190, 183, 0.18)'
  ctx.lineWidth = 2
  ctx.setLineDash([7, 15])
  ctx.beginPath()
  ctx.arc(1810, 720, 500, -1.1, 1.82)
  ctx.stroke()
  ctx.beginPath()
  ctx.arc(1810, 720, 620, 2.18, 4.52)
  ctx.stroke()
  ctx.setLineDash([])
  ctx.strokeStyle = 'rgba(170, 205, 195, 0.32)'
  ;[
    [1260, 298], [2340, 310], [2294, 1112], [1268, 1134],
  ].forEach(([x, y]) => {
    ctx.beginPath()
    ctx.moveTo(x - 18, y)
    ctx.lineTo(x + 18, y)
    ctx.moveTo(x, y - 18)
    ctx.lineTo(x, y + 18)
    ctx.stroke()
  })
  for (let x = 1200; x <= 2380; x += 40) {
    const tick = x % 200 === 0 ? 15 : 7
    ctx.beginPath()
    ctx.moveTo(x, 1194)
    ctx.lineTo(x, 1194 + tick)
    ctx.stroke()
  }
  ctx.restore()
}

function drawShipLabel(
  ctx: CanvasRenderingContext2D,
  shipId: string,
  x: number,
  y: number,
  align: CanvasTextAlign = 'left',
): void {
  const ship = shipById.get(shipId)
  if (!ship) return
  ctx.save()
  ctx.textAlign = align
  ctx.fillStyle = colors.paper
  setFont(ctx, 19, 500)
  ctx.fillText(ship.name, x, y)
  ctx.fillStyle = colors.muted
  setFont(ctx, 13, 400, true)
  ctx.fillText(`${ship.series} / ${ship.id.toUpperCase()}`, x, y + 26)
  ctx.restore()
}

function drawFormation(ctx: CanvasRenderingContext2D, assets: ImageAssets, settings: PosterSettings): void {
  const escorts = ['astd_xc_102', 'astd_zw_001', 'astd_zw_002', 'astd_zw_102']
    .filter((id) => id !== settings.mainShipId)
  if (settings.mainShipId !== 'astd_xc_001') escorts.push('astd_xc_001')

  const slots = [
    { x: 1420, y: 500, width: 430, rotation: -12, labelX: 1190, labelY: 354 },
    { x: 2130, y: 374, width: 290, rotation: 10, labelX: 2220, labelY: 284 },
    { x: 2180, y: 896, width: 285, rotation: -9, labelX: 2310, labelY: 1046 },
    { x: 1430, y: 1030, width: 340, rotation: 8, labelX: 1170, labelY: 1170 },
  ]

  escorts.forEach((id, index) => {
    const ship = shipById.get(id)
    const slot = slots[index]
    if (!ship || !slot) return
    drawShip(ctx, ship, assets, {
      x: slot.x,
      y: slot.y,
      width: slot.width,
      rotation: slot.rotation,
      bloom: settings.bloom,
    })
    if (settings.showAnnotations) {
      drawShipLabel(ctx, id, slot.labelX, slot.labelY)
    }
  })

  const main = shipById.get(settings.mainShipId)
  if (main) {
    drawShip(ctx, main, assets, {
      x: 1800,
      y: 704,
      width: main.id === 'astd_xc_102' ? 650 : 535,
      rotation: 8,
      bloom: settings.bloom,
      halo: 0.12,
    })
    if (settings.showAnnotations) {
      ctx.strokeStyle = 'rgba(186, 213, 204, 0.35)'
      ctx.beginPath()
      ctx.moveTo(1780, 1050)
      ctx.lineTo(1780, 1110)
      ctx.lineTo(1930, 1110)
      ctx.stroke()
      drawShipLabel(ctx, main.id, 1950, 1092)
    }
  }
}

export function drawHero(
  ctx: CanvasRenderingContext2D,
  assets: ImageAssets,
  settings: PosterSettings,
): void {
  drawBackground(ctx, assets, settings)
  if (settings.showGrid) drawGrid(ctx)
  if (settings.showAnnotations) drawFormationMarks(ctx)
  drawFormation(ctx, assets, settings)
  drawBrand(ctx, assets)
  drawEditorial(ctx)
  if (settings.showAnnotations) {
    ctx.fillStyle = colors.faint
    setFont(ctx, 13, 400, true)
    ctx.fillText('RA 18h 36m  /  DEC +38° 47′', 2020, 1240)
    ctx.font = `12px ${fontMono}`
    ctx.fillText('FOSSIC.VISUALIZATION', 150, 250)
  }
  if (settings.showSafeArea) {
    ctx.strokeStyle = 'rgba(184, 216, 202, 0.15)'
    drawCornerFrame(ctx, 110, 72, 2340, 1300, 16)
    drawSafeArea(ctx, width, height)
  }
}

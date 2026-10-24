import { classLabels, shipById } from '../data/ships'
import type { Ship, ShipClass, ShipSeries } from '../data/ships'
import { drawShip } from './drawShip'
import type { ImageAssets, PosterSettings } from './shared'
import {
  colors,
  drawCover,
  drawSafeArea,
  drawTrackedText,
  setFont,
} from './shared'

const width = 1920
const height = 2400
const shipScale = 1.25

interface Placement {
  id: string
  shipClass: ShipClass
  x: number
  y: number
  labelY: number
}

const arcPlacements: Placement[] = [
  { id: 'astd_xc_001', shipClass: 'CAPITAL_SHIP', x: 500, y: 480, labelY: 880 },
  { id: 'astd_xc_102', shipClass: 'CAPITAL_SHIP', x: 1320, y: 500, labelY: 880 },
  { id: 'astd_xc_101', shipClass: 'CRUISER', x: 340, y: 1240, labelY: 1500 },
  { id: 'astd_xc_002', shipClass: 'DESTROYER', x: 920, y: 1240, labelY: 1500 },
  { id: 'astd_xc_103', shipClass: 'DESTROYER', x: 1430, y: 1240, labelY: 1500 },
  { id: 'astd_xc_104', shipClass: 'FRIGATE', x: 560, y: 1850, labelY: 2070 },
  { id: 'astd_lh_001', shipClass: 'FRIGATE', x: 1280, y: 1850, labelY: 2070 },
]

const lensPlacements: Placement[] = [
  { id: 'astd_zw_102', shipClass: 'CAPITAL_SHIP', x: 960, y: 500, labelY: 800 },
  { id: 'astd_zw_001', shipClass: 'CRUISER', x: 540, y: 1200, labelY: 1480 },
  { id: 'astd_zw_002', shipClass: 'CRUISER', x: 1320, y: 1200, labelY: 1480 },
  { id: 'astd_zw_101', shipClass: 'DESTROYER', x: 400, y: 1850, labelY: 2070 },
  { id: 'astd_zw_103', shipClass: 'DESTROYER', x: 960, y: 1850, labelY: 2070 },
  { id: 'astd_lh_002', shipClass: 'FRIGATE', x: 1500, y: 1850, labelY: 2070 },
]

function drawBackground(ctx: CanvasRenderingContext2D, assets: ImageAssets, settings: PosterSettings): void {
  ctx.fillStyle = '#070b0e'
  ctx.fillRect(0, 0, width, height)
  const background = assets.get('/assets/backgrounds/deep-space-atlas.png')
  if (background) {
    ctx.save()
    ctx.globalAlpha = 0.56
    drawCover(ctx, background, width, height)
    ctx.restore()
  }
  const brightness = settings.backgroundBrightness / 100
  ctx.fillStyle = brightness <= 1
    ? `rgba(2, 5, 8, ${1 - brightness})`
    : `rgba(151, 174, 181, ${(brightness - 1) * 0.18})`
  ctx.fillRect(0, 0, width, height)
  const shade = ctx.createLinearGradient(0, 0, 0, height)
  shade.addColorStop(0, 'rgba(4, 8, 11, 0.12)')
  shade.addColorStop(0.5, 'rgba(4, 8, 11, 0.38)')
  shade.addColorStop(1, 'rgba(4, 8, 11, 0.72)')
  ctx.fillStyle = shade
  ctx.fillRect(0, 0, width, height)
  if (!settings.showGrid) return
  ctx.strokeStyle = 'rgba(148, 179, 174, 0.045)'
  ctx.lineWidth = 1
  for (let x = 120; x < width; x += 160) {
    ctx.beginPath()
    ctx.moveTo(x + 0.5, 160)
    ctx.lineTo(x + 0.5, height - 100)
    ctx.stroke()
  }
  for (let y = 200; y < height; y += 160) {
    ctx.beginPath()
    ctx.moveTo(80, y + 0.5)
    ctx.lineTo(width - 80, y + 0.5)
    ctx.stroke()
  }
}

function drawGeometry(ctx: CanvasRenderingContext2D, series: ShipSeries): void {
  const accent = series === 'ARC' ? colors.arc : colors.lens
  ctx.save()
  ctx.fillStyle = accent
  ctx.globalAlpha = 0.055
  ctx.beginPath()
  ctx.moveTo(1580, 270)
  ctx.lineTo(1760, 270)
  ctx.lineTo(1870, 490)
  ctx.lineTo(1760, 710)
  ctx.lineTo(1580, 710)
  ctx.lineTo(1690, 490)
  ctx.closePath()
  ctx.fill()
  ctx.beginPath()
  ctx.moveTo(1730, 1170)
  ctx.lineTo(1920, 1030)
  ctx.lineTo(1920, 1450)
  ctx.lineTo(1810, 1530)
  ctx.closePath()
  ctx.fill()
  ctx.globalAlpha = 0.16
  ctx.lineWidth = 5
  ctx.strokeStyle = accent
  for (let x = 70; x < 330; x += 42) {
    ctx.beginPath()
    ctx.moveTo(x, 2208)
    ctx.lineTo(x + 70, 2148)
    ctx.stroke()
  }
  ctx.globalAlpha = 0.55
  ctx.fillRect(1600, 158, 82, 4)
  ctx.fillRect(1700, 158, 34, 4)
  ctx.restore()
}

function drawHeader(
  ctx: CanvasRenderingContext2D,
  series: ShipSeries,
  count: number,
): void {
  const accent = series === 'ARC' ? colors.arc : colors.lens
  ctx.fillStyle = accent
  ctx.fillRect(96, 58, 8, 88)
  ctx.fillStyle = colors.paper
  setFont(ctx, 76, 500)
  ctx.fillText(series === 'ARC' ? '星坠设计' : '紫菀设计', 132, 136)
  ctx.textAlign = 'right'
  ctx.fillStyle = accent
  setFont(ctx, 42, 500, true)
  ctx.fillText(series, 1736, 116)
  ctx.fillStyle = colors.muted
  setFont(ctx, 32, 400, true)
  ctx.fillText(String(count).padStart(2, '0'), 1824, 116)
  ctx.textAlign = 'left'
  ctx.strokeStyle = 'rgba(174, 201, 193, 0.24)'
  ctx.beginPath()
  ctx.moveTo(96, 178.5)
  ctx.lineTo(1824, 178.5)
  ctx.stroke()
}

function drawSection(
  ctx: CanvasRenderingContext2D,
  shipClass: ShipClass,
  y: number,
  startX = 96,
  endX = 1824,
): void {
  ctx.fillStyle = colors.muted
  setFont(ctx, 34, 400)
  ctx.fillText(classLabels[shipClass], startX, y)
  ctx.fillStyle = colors.faint
  setFont(ctx, 26, 400, true)
  ctx.fillText(shipClass.replace('_', ' '), startX + 150, y)
  ctx.strokeStyle = 'rgba(151, 180, 174, 0.2)'
  ctx.beginPath()
  ctx.moveTo(startX + 400, y - 10.5)
  ctx.lineTo(endX, y - 10.5)
  ctx.stroke()
}

function drawLabel(
  ctx: CanvasRenderingContext2D,
  ship: Ship,
  x: number,
  y: number,
  showAnnotations: boolean,
): void {
  ctx.save()
  ctx.textAlign = 'left'
  if (showAnnotations) {
    ctx.strokeStyle = 'rgba(151, 180, 174, 0.38)'
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.moveTo(x - 54, y - 91.5)
    ctx.lineTo(x + 54, y - 91.5)
    ctx.moveTo(x, y - 104)
    ctx.lineTo(x, y - 80)
    ctx.stroke()
  }
  setFont(ctx, 56, 500)
  const nameWidth = ctx.measureText(ship.name).width
  setFont(ctx, 52, 500, true)
  const separatorWidth = ctx.measureText('//').width
  setFont(ctx, 30, 400)
  const designationWidth = ctx.measureText(ship.designation).width
  const gap = 28
  const totalWidth = nameWidth + separatorWidth + designationWidth + gap * 2
  let cursor = x - totalWidth / 2
  ctx.fillStyle = colors.paper
  setFont(ctx, 56, 500)
  ctx.fillText(ship.name, cursor, y)
  cursor += nameWidth + gap
  ctx.fillStyle = ship.series === 'ARC' ? colors.arc : colors.lens
  setFont(ctx, 52, 500, true)
  ctx.fillText('//', cursor, y)
  cursor += separatorWidth + gap
  ctx.fillStyle = colors.muted
  setFont(ctx, 30, 400)
  ctx.fillText(ship.designation, cursor, y)
  if (showAnnotations) {
    const id = ship.id.toUpperCase()
    const tracking = 3
    ctx.fillStyle = colors.muted
    setFont(ctx, 28, 400, true)
    const idWidth = Array.from(id).reduce((sum, character) => sum + ctx.measureText(character).width, 0) + tracking * (id.length - 1)
    drawTrackedText(ctx, id, x - idWidth / 2, y + 54, tracking)
  }
  ctx.restore()
}

function drawPlacements(
  ctx: CanvasRenderingContext2D,
  placements: Placement[],
  assets: ImageAssets,
  settings: PosterSettings,
): void {
  placements.forEach((placement) => {
    const ship = shipById.get(placement.id)
    if (!ship) throw new Error(`图鉴缺少舰体数据：${placement.id}`)
    const sprite = assets.get(`/assets/ships/${ship.sprite}`)
    if (!sprite) throw new Error(`图鉴缺少舰体主贴图：${placement.id}`)
    const drawn = drawShip(ctx, ship, assets, {
      x: placement.x,
      y: placement.y,
      width: sprite.naturalWidth * shipScale,
      bloom: settings.bloom,
      halo: 0.08,
    })
    if (!drawn) throw new Error(`图鉴舰体绘制失败，检查主贴图与发光层：${placement.id}`)
    drawLabel(ctx, ship, placement.x, placement.labelY, settings.showAnnotations)
  })
}

function drawFooter(ctx: CanvasRenderingContext2D, series: ShipSeries, count: number, y: number): void {
  ctx.strokeStyle = 'rgba(174, 201, 193, 0.2)'
  ctx.beginPath()
  ctx.moveTo(96, y + 0.5)
  ctx.lineTo(1824, y + 0.5)
  ctx.stroke()
  ctx.fillStyle = colors.faint
  setFont(ctx, 26, 400)
  ctx.fillText(series === 'ARC' ? '星坠设计' : '紫菀设计', 96, y + 52)
  ctx.textAlign = 'right'
  setFont(ctx, 24, 400, true)
  ctx.fillText(`${series} / ${String(count).padStart(2, '0')}`, 1824, y + 52)
  ctx.textAlign = 'left'
}

export function drawAtlas(
  ctx: CanvasRenderingContext2D,
  assets: ImageAssets,
  settings: PosterSettings,
  series: ShipSeries,
): void {
  drawBackground(ctx, assets, settings)
  drawGeometry(ctx, series)
  const placements = series === 'ARC' ? arcPlacements : lensPlacements
  drawHeader(ctx, series, placements.length)
  if (series === 'ARC') {
    drawSection(ctx, 'CAPITAL_SHIP', 216)
    drawSection(ctx, 'CRUISER', 1030, 96, 760)
    drawSection(ctx, 'DESTROYER', 1030, 840, 1824)
    drawSection(ctx, 'FRIGATE', 1650)
  } else {
    drawSection(ctx, 'CAPITAL_SHIP', 216)
    drawSection(ctx, 'CRUISER', 960)
    drawSection(ctx, 'DESTROYER', 1650, 96, 1050)
    drawSection(ctx, 'FRIGATE', 1650, 1200, 1824)
  }
  drawPlacements(ctx, placements, assets, settings)
  drawFooter(ctx, series, placements.length, 2250)
  if (settings.showSafeArea) drawSafeArea(ctx, width, height, 36)
}

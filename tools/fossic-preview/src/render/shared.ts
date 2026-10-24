import type { Ship } from '../data/ships'

export type PosterLayout = 'hero' | 'atlasArc' | 'atlasLens' | 'banner'
export type ExportScale = 0.25 | 0.5 | 1 | 2

export interface PosterSettings {
  layout: PosterLayout
  mainShipId: string
  bloom: number
  backgroundBrightness: number
  showGrid: boolean
  showAnnotations: boolean
  showSafeArea: boolean
  bannerTitle: string
  bannerSide: string
  bannerKicker: string
  bannerFps: number
}

export type ImageAssets = ReadonlyMap<string, HTMLImageElement>

export interface LayoutSpec {
  width: number
  height: number
}

export const layoutSpecs: Record<PosterLayout, LayoutSpec> = {
  hero: { width: 2560, height: 1440 },
  atlasArc: { width: 1920, height: 2400 },
  atlasLens: { width: 1920, height: 2400 },
  banner: { width: 1920, height: 360 },
}

export const colors = {
  paper: '#ececf2',
  muted: '#9996ad',
  faint: '#615d73',
  accent: '#c6b5ee',
  arc: '#82dcf2',
  lens: '#c69af0',
}

export const fontSans = '"Rubik", "Noto Sans SC", "Source Han Sans SC", "Microsoft YaHei", "PingFang SC", sans-serif'
export const fontMono = fontSans

export function shipAsset(ship: Ship, bloom = false): string {
  return `/assets/ships/${bloom ? ship.bloom : ship.sprite}`
}

export function setFont(
  ctx: CanvasRenderingContext2D,
  size: number,
  weight = 400,
  mono = false,
): void {
  ctx.font = `${weight} ${size}px ${mono ? fontMono : fontSans}`
}

export function drawCover(
  ctx: CanvasRenderingContext2D,
  image: HTMLImageElement,
  width: number,
  height: number,
): void {
  const scale = Math.max(width / image.naturalWidth, height / image.naturalHeight)
  const drawWidth = image.naturalWidth * scale
  const drawHeight = image.naturalHeight * scale
  ctx.drawImage(image, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight)
}

export function drawTrackedText(
  ctx: CanvasRenderingContext2D,
  text: string,
  x: number,
  y: number,
  tracking: number,
): void {
  let cursor = x
  for (const character of text) {
    ctx.fillText(character, cursor, y)
    cursor += ctx.measureText(character).width + tracking
  }
}

export function drawCornerFrame(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  cut = 12,
): void {
  ctx.beginPath()
  ctx.moveTo(x + cut, y)
  ctx.lineTo(x + width, y)
  ctx.lineTo(x + width, y + height - cut)
  ctx.lineTo(x + width - cut, y + height)
  ctx.lineTo(x, y + height)
  ctx.lineTo(x, y + cut)
  ctx.closePath()
  ctx.stroke()
}

export function drawSafeArea(
  ctx: CanvasRenderingContext2D,
  width: number,
  height: number,
  fontSize = 18,
): void {
  const margin = Math.round(Math.min(width, height) * 0.045)
  ctx.save()
  ctx.strokeStyle = 'rgba(225, 190, 115, 0.55)'
  ctx.lineWidth = 2
  ctx.setLineDash([12, 12])
  ctx.strokeRect(margin, margin, width - margin * 2, height - margin * 2)
  ctx.setLineDash([])
  ctx.fillStyle = 'rgba(225, 190, 115, 0.8)'
  setFont(ctx, fontSize, 500, true)
  ctx.fillText(`SAFE / ${margin}px`, margin + 14, Math.max(20, fontSize + 4, margin - 12))
  ctx.restore()
}

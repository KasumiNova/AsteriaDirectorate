import type { BeamLayer, BeamPreset } from '../model/beamPreset';
import { beamNoiseAt } from './beamNoise';
import { lerp, sampleBeamPath, type BeamSample } from './beamGeometry';

export interface BeamRenderOptions {
  width: number;
  height: number;
  timeSeconds: number;
}

export interface BeamRenderSummary {
  enabledLayerCount: number;
  sampleCount: number;
  bounds: BeamBounds;
}

interface Rgb {
  r: number;
  g: number;
  b: number;
}

interface BeamBounds {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

const PREVIEW_PADDING = 96;

interface PreviewTransform {
  sourceCx: number;
  sourceCy: number;
  targetCx: number;
  targetCy: number;
  scale: number;
}

export function renderBeamPreview(
  ctx: CanvasRenderingContext2D,
  preset: BeamPreset,
  options: BeamRenderOptions,
): BeamRenderSummary {
  const enabledLayers = preset.layers.filter((layer) => layer.enabled);
  const sourceSamples = sampleBeamPath(preset);
  const transform = previewTransform(sourceSamples, options.width, options.height);
  const samples = transformSamples(sourceSamples, transform);
  const controlPoints = preset.controlPoints.map((point) => transformPoint(point, transform));

  ctx.clearRect(0, 0, options.width, options.height);
  drawBackdrop(ctx, options.width, options.height);

  for (const layer of enabledLayers) {
    drawLayerBloom(ctx, samples, layer);
    drawLayerCore(ctx, samples, layer, options.timeSeconds);
  }

  drawControlPoints(ctx, controlPoints);
  return { enabledLayerCount: enabledLayers.length, sampleCount: samples.length, bounds: beamBounds(samples) };
}

function drawBackdrop(ctx: CanvasRenderingContext2D, width: number, height: number): void {
  const gradient = ctx.createLinearGradient(0, 0, width, height);
  gradient.addColorStop(0, '#10131b');
  gradient.addColorStop(1, '#080a0f');
  ctx.fillStyle = gradient;
  ctx.fillRect(0, 0, width, height);

  ctx.save();
  ctx.strokeStyle = 'rgba(150, 176, 205, 0.08)';
  ctx.lineWidth = 1;
  for (let x = 0; x <= width; x += 40) {
    ctx.beginPath();
    ctx.moveTo(x, 0);
    ctx.lineTo(x, height);
    ctx.stroke();
  }
  for (let y = 0; y <= height; y += 40) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(width, y);
    ctx.stroke();
  }
  ctx.restore();
}

function drawLayerBloom(ctx: CanvasRenderingContext2D, samples: BeamSample[], layer: BeamLayer): void {
  if (layer.bloomStrength <= 0) {
    return;
  }

  const passes = [
    { widthScale: 3.1, alpha: 0.055, blur: 24 },
    { widthScale: 2.2, alpha: 0.075, blur: 14 },
    { widthScale: 1.45, alpha: 0.10, blur: 7 },
  ];

  ctx.save();
  ctx.globalCompositeOperation = compositeFor(layer.blendMode);

  for (const pass of passes) {
    ctx.filter = `blur(${pass.blur}px)`;
    strokeBeamPath(ctx, samples, layer, pass.widthScale, pass.alpha * layer.bloomStrength);
  }

  ctx.filter = 'none';
  ctx.restore();
}

function drawLayerCore(
  ctx: CanvasRenderingContext2D,
  samples: BeamSample[],
  layer: BeamLayer,
  timeSeconds: number,
): void {
  ctx.save();
  ctx.globalCompositeOperation = compositeFor(layer.blendMode);
  fillBeamMesh(ctx, samples, layer, 1, 1, timeSeconds);
  ctx.restore();
}

function fillBeamMesh(
  ctx: CanvasRenderingContext2D,
  samples: BeamSample[],
  layer: BeamLayer,
  widthScale: number,
  alphaScale: number,
  timeSeconds: number,
): void {
  for (let index = 0; index < samples.length - 1; index += 1) {
    const a = samples[index];
    const b = samples[index + 1];
    const progress = (a.progress + b.progress) * 0.5;
    const widthA = lerp(layer.widthStart, layer.widthEnd, a.progress) * widthScale;
    const widthB = lerp(layer.widthStart, layer.widthEnd, b.progress) * widthScale;
    const alphaNoise = 1 + (beamNoiseAt(layer.id, progress, timeSeconds * layer.textureSpeed, layer.noiseScale) - 0.5) * layer.noiseStrength;
    const alpha = clamp(lerp(layer.emissiveStart, layer.emissiveEnd, progress) * alphaNoise * alphaScale, 0, 1);
    const color = interpolateColor(layer.colorStart, layer.colorEnd, progress);
    ctx.fillStyle = rgba(color, alpha);
    ctx.beginPath();
    ctx.moveTo(a.x + a.normalX * widthA * 0.5, a.y + a.normalY * widthA * 0.5);
    ctx.lineTo(b.x + b.normalX * widthB * 0.5, b.y + b.normalY * widthB * 0.5);
    ctx.lineTo(b.x - b.normalX * widthB * 0.5, b.y - b.normalY * widthB * 0.5);
    ctx.lineTo(a.x - a.normalX * widthA * 0.5, a.y - a.normalY * widthA * 0.5);
    ctx.closePath();
    ctx.fill();
  }
}

function strokeBeamPath(
  ctx: CanvasRenderingContext2D,
  samples: BeamSample[],
  layer: BeamLayer,
  widthScale: number,
  alpha: number,
): void {
  if (samples.length < 2) {
    return;
  }

  const color = interpolateColor(layer.colorStart, layer.colorEnd, 0.45);
  const width = Math.max(1, ((layer.widthStart + layer.widthEnd) * 0.5) * widthScale);
  ctx.strokeStyle = rgba(color, alpha);
  ctx.lineWidth = width;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(samples[0].x, samples[0].y);
  for (let index = 1; index < samples.length; index += 1) {
    ctx.lineTo(samples[index].x, samples[index].y);
  }
  ctx.stroke();
}

function drawControlPoints(ctx: CanvasRenderingContext2D, points: BeamPreset['controlPoints']): void {
  ctx.save();
  ctx.fillStyle = 'rgba(255, 255, 255, 0.68)';
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.32)';
  ctx.lineWidth = 1;
  for (const point of points) {
    ctx.beginPath();
    ctx.arc(point.x, point.y, 4, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
  }
  ctx.restore();
}

function previewTransform(samples: BeamSample[], width: number, height: number): PreviewTransform {
  if (samples.length === 0) {
    return { sourceCx: 0, sourceCy: 0, targetCx: width * 0.5, targetCy: height * 0.5, scale: 1 };
  }

  const bounds = beamBounds(samples);
  const beamWidth = Math.max(1, bounds.maxX - bounds.minX);
  const beamHeight = Math.max(1, bounds.maxY - bounds.minY);
  const availableWidth = Math.max(1, width - PREVIEW_PADDING * 2);
  const availableHeight = Math.max(1, height - PREVIEW_PADDING * 2);
  const scale = Math.min(1, availableWidth / beamWidth, availableHeight / beamHeight);
  const sourceCx = (bounds.minX + bounds.maxX) * 0.5;
  const sourceCy = (bounds.minY + bounds.maxY) * 0.5;
  const targetCx = width * 0.5;
  const targetCy = height * 0.5;
  return { sourceCx, sourceCy, targetCx, targetCy, scale };
}

function transformSamples(samples: BeamSample[], transform: PreviewTransform): BeamSample[] {
  return samples.map((sample) => ({
    ...sample,
    x: transform.targetCx + (sample.x - transform.sourceCx) * transform.scale,
    y: transform.targetCy + (sample.y - transform.sourceCy) * transform.scale,
  }));
}

function transformPoint(point: BeamPreset['controlPoints'][number], transform: PreviewTransform): BeamPreset['controlPoints'][number] {
  return {
    x: transform.targetCx + (point.x - transform.sourceCx) * transform.scale,
    y: transform.targetCy + (point.y - transform.sourceCy) * transform.scale,
  };
}

function beamBounds(samples: BeamSample[]): BeamBounds {
  if (samples.length === 0) {
    return { minX: 0, minY: 0, maxX: 0, maxY: 0 };
  }
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;
  for (const sample of samples) {
    minX = Math.min(minX, sample.x);
    minY = Math.min(minY, sample.y);
    maxX = Math.max(maxX, sample.x);
    maxY = Math.max(maxY, sample.y);
  }
  return { minX, minY, maxX, maxY };
}

function compositeFor(mode: BeamLayer['blendMode']): GlobalCompositeOperation {
  if (mode === 'additive') {
    return 'lighter';
  }
  if (mode === 'screen') {
    return 'screen';
  }
  return 'source-over';
}

function interpolateColor(start: string, end: string, progress: number): Rgb {
  const a = parseHexColor(start);
  const b = parseHexColor(end);
  return {
    r: Math.round(lerp(a.r, b.r, progress)),
    g: Math.round(lerp(a.g, b.g, progress)),
    b: Math.round(lerp(a.b, b.b, progress)),
  };
}

function parseHexColor(color: string): Rgb {
  const hex = color.replace('#', '');
  const normalized = hex.length === 3 ? hex.split('').map((part) => `${part}${part}`).join('') : hex;
  const value = Number.parseInt(normalized, 16);
  if (!Number.isFinite(value)) {
    return { r: 255, g: 255, b: 255 };
  }
  return {
    r: (value >> 16) & 255,
    g: (value >> 8) & 255,
    b: value & 255,
  };
}

function rgba(color: Rgb, alpha: number): string {
  return `rgba(${color.r}, ${color.g}, ${color.b}, ${clamp(alpha, 0, 1).toFixed(3)})`;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

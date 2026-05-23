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
}

interface Rgb {
  r: number;
  g: number;
  b: number;
}

export function renderBeamPreview(
  ctx: CanvasRenderingContext2D,
  preset: BeamPreset,
  options: BeamRenderOptions,
): BeamRenderSummary {
  const enabledLayers = preset.layers.filter((layer) => layer.enabled);
  const samples = sampleBeamPath(preset);

  ctx.clearRect(0, 0, options.width, options.height);
  drawBackdrop(ctx, options.width, options.height);

  for (const layer of enabledLayers) {
    drawLayerBloom(ctx, samples, layer);
    drawLayerCore(ctx, samples, layer, options.timeSeconds);
  }

  drawControlPoints(ctx, preset.controlPoints);
  return { enabledLayerCount: enabledLayers.length, sampleCount: samples.length };
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
    { widthScale: 2.7, alpha: 0.09 },
    { widthScale: 1.8, alpha: 0.14 },
    { widthScale: 1.25, alpha: 0.18 },
  ];

  ctx.save();
  ctx.globalCompositeOperation = compositeFor(layer.blendMode);
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  for (const pass of passes) {
    strokeSamples(ctx, samples, layer, pass.widthScale, pass.alpha * layer.bloomStrength);
  }

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

  for (let index = 0; index < samples.length - 1; index += 1) {
    const a = samples[index];
    const b = samples[index + 1];
    const progress = (a.progress + b.progress) * 0.5;
    const width = lerp(layer.widthStart, layer.widthEnd, progress);
    const alphaNoise = 1 + (beamNoiseAt(layer.id, progress, timeSeconds * layer.textureSpeed, layer.noiseScale) - 0.5) * layer.noiseStrength;
    const alpha = clamp(lerp(layer.emissiveStart, layer.emissiveEnd, progress) * alphaNoise, 0, 1);
    const color = interpolateColor(layer.colorStart, layer.colorEnd, progress);

    ctx.fillStyle = rgba(color, alpha);
    ctx.beginPath();
    ctx.moveTo(a.x + a.normalX * width * 0.5, a.y + a.normalY * width * 0.5);
    ctx.lineTo(b.x + b.normalX * width * 0.5, b.y + b.normalY * width * 0.5);
    ctx.lineTo(b.x - b.normalX * width * 0.5, b.y - b.normalY * width * 0.5);
    ctx.lineTo(a.x - a.normalX * width * 0.5, a.y - a.normalY * width * 0.5);
    ctx.closePath();
    ctx.fill();
  }

  ctx.restore();
}

function strokeSamples(
  ctx: CanvasRenderingContext2D,
  samples: BeamSample[],
  layer: BeamLayer,
  widthScale: number,
  alpha: number,
): void {
  for (let index = 0; index < samples.length - 1; index += 1) {
    const a = samples[index];
    const b = samples[index + 1];
    const progress = (a.progress + b.progress) * 0.5;
    const color = interpolateColor(layer.colorStart, layer.colorEnd, progress);
    ctx.strokeStyle = rgba(color, alpha);
    ctx.lineWidth = Math.max(1, lerp(layer.widthStart, layer.widthEnd, progress) * widthScale);
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.lineTo(b.x, b.y);
    ctx.stroke();
  }
}

function drawControlPoints(ctx: CanvasRenderingContext2D, points: BeamPreset['controlPoints']): void {
  ctx.save();
  ctx.fillStyle = 'rgba(255, 255, 255, 0.82)';
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.42)';
  ctx.lineWidth = 1;
  for (const point of points) {
    ctx.beginPath();
    ctx.arc(point.x, point.y, 4, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
  }
  ctx.restore();
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

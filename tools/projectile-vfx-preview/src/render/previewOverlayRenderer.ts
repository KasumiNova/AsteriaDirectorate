import {
  BoxUtilPreviewPreset,
  ProjectileVfxGlowLayerConfig,
  ProjectileVfxHeadLayerConfig,
  ProjectileVfxMistLayerConfig,
  ProjectileVfxSideWispLayerConfig,
  Rgba,
  TrailEntityConfig,
  TrailRibbonDecorationConfig,
  Vec2,
} from '../model/preset';
import {
  projectileVfxBodyGradientStops,
  projectileVfxGlowLineWidth,
  projectileVfxHeadFillLayout,
  projectileVfxSideWispLocalPaths,
  projectileVfxWidthBase,
} from './projectileVfxLayout';

export interface PreviewOverlayLayerVisibility {
  trail: boolean;
  head: boolean;
  glow: boolean;
  mist: boolean;
  sideWisps: boolean;
  ribbon: boolean;
}

export const DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY: PreviewOverlayLayerVisibility = {
  trail: true,
  head: true,
  glow: true,
  mist: true,
  sideWisps: true,
  ribbon: true,
};

export type PreviewTrajectoryMode = 'straight' | 'curved';

export interface PreviewOverlayRenderer {
  resize(width: number, height: number): void;
  render(
    preset: BoxUtilPreviewPreset,
    timeSeconds: number,
    layerVisibility?: Partial<PreviewOverlayLayerVisibility>,
    trajectoryMode?: PreviewTrajectoryMode,
  ): void;
}

export function createPreviewOverlayRenderer(canvas: HTMLCanvasElement): PreviewOverlayRenderer | null {
  const context = canvas.getContext('2d');
  if (!context) {
    return null;
  }

  return new CanvasPreviewOverlayRenderer(canvas, context);
}

class CanvasPreviewOverlayRenderer implements PreviewOverlayRenderer {
  private width = 1;
  private height = 1;

  constructor(private readonly canvas: HTMLCanvasElement, private readonly context: CanvasRenderingContext2D) {}

  resize(width: number, height: number): void {
    if (this.canvas.width !== width) {
      this.canvas.width = width;
    }
    if (this.canvas.height !== height) {
      this.canvas.height = height;
    }
    this.width = width;
    this.height = height;
  }

  render(
    preset: BoxUtilPreviewPreset,
    timeSeconds: number,
    layerVisibility: Partial<PreviewOverlayLayerVisibility> = {},
    trajectoryMode: PreviewTrajectoryMode = 'straight',
  ): void {
    const ctx = this.context;
    const visibility = { ...DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY, ...layerVisibility };
    ctx.clearRect(0, 0, this.width, this.height);
    drawBackdrop(ctx, this.width, this.height, timeSeconds);
    ctx.save();
    ctx.globalCompositeOperation = 'screen';

    for (const trail of preset.trailEntities) {
      drawTrailLayers(ctx, trail, preset, timeSeconds, this.width, this.height, visibility, trajectoryMode);
    }

    ctx.restore();
  }
}

function drawBackdrop(ctx: CanvasRenderingContext2D, width: number, height: number, timeSeconds: number): void {
  const vignette = ctx.createRadialGradient(width * 0.5, height * 0.42, Math.min(width, height) * 0.08, width * 0.5, height * 0.42, Math.max(width, height) * 0.72);
  vignette.addColorStop(0, 'rgba(11, 18, 34, 0.42)');
  vignette.addColorStop(1, 'rgba(2, 5, 11, 0.96)');
  ctx.fillStyle = vignette;
  ctx.fillRect(0, 0, width, height);

  ctx.save();
  ctx.globalCompositeOperation = 'screen';
  ctx.strokeStyle = 'rgba(126, 182, 255, 0.06)';
  ctx.lineWidth = 1;
  const spacing = 48;
  const drift = (timeSeconds * 14) % spacing;
  for (let x = -spacing; x <= width + spacing; x += spacing) {
    ctx.beginPath();
    ctx.moveTo(x + drift * 0.16, 0);
    ctx.lineTo(x + drift * 0.16, height);
    ctx.stroke();
  }
  for (let y = -spacing; y <= height + spacing; y += spacing) {
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(width, y);
    ctx.stroke();
  }

  ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
  ctx.beginPath();
  ctx.moveTo(0, height * 0.5);
  ctx.lineTo(width, height * 0.5);
  ctx.stroke();
  ctx.restore();
}

function drawTrailLayers(
  ctx: CanvasRenderingContext2D,
  trail: TrailEntityConfig,
  preset: BoxUtilPreviewPreset,
  timeSeconds: number,
  width: number,
  height: number,
  visibility: PreviewOverlayLayerVisibility,
  trajectoryMode: PreviewTrajectoryMode,
): void {
  if (trail.nodes.length < 2) {
    return;
  }

  const flightDirection = normalize(preset.simulation.projectileVelocity);
  const flightNormal: Vec2 = [-flightDirection[1], flightDirection[0]];
  const flightTrack = computeFlightTrack(trail, preset, timeSeconds, width, height, flightDirection, flightNormal, trajectoryMode);

  const history = buildTrailHistory(trail, preset, timeSeconds, width, height, flightDirection, flightNormal, flightTrack, trajectoryMode);
  drawTravelBeam(ctx, trail, preset, timeSeconds, flightTrack, width, height, flightDirection, flightNormal, history, visibility);
}

interface FlightTrack {
  head: Vec2;
  tail: Vec2;
  center: Vec2;
  length: number;
  progress: number;
  elapsed: number;
  flightProgress: number;
  dissolve: number;
  visibleLength: number;
  beamAlpha: number;
}

const MAX_TRAIL_HISTORY = 512;

function computeFlightTrack(
  trail: TrailEntityConfig,
  preset: BoxUtilPreviewPreset,
  timeSeconds: number,
  width: number,
  height: number,
  direction: Vec2,
  normal: Vec2,
  trajectoryMode: PreviewTrajectoryMode,
): FlightTrack {
  const duration = Math.max(preset.timeline.durationSeconds, 1.2);
  const elapsed = preset.simulation.loop ? timeSeconds % duration : clamp(timeSeconds, 0, duration);
  const progress = clamp(elapsed / duration, 0, 1);
  const flightEndSeconds = duration * preset.lifecycle.flightEndRatio;
  const dissolveStartSeconds = duration * preset.lifecycle.dissolveStartRatio;
  const flightRange = preset.lifecycle.preDissolveFraction;
  const dissolveRange = 1 - preset.lifecycle.preDissolveFraction;
  const dissolveDuration = Math.max(duration - dissolveStartSeconds, 0.0001);
  const flightSpeed = flightRange / Math.max(flightEndSeconds, 0.0001);
  const dissolveStartSpeed = flightSpeed;
  const dissolveEndSpeed = flightSpeed * 0.25;
  const dissolveStartSlope = (dissolveStartSpeed * dissolveDuration) / Math.max(dissolveRange, 0.0001);
  const dissolveEndSlope = (dissolveEndSpeed * dissolveDuration) / Math.max(dissolveRange, 0.0001);
  const flightProgress = elapsed <= dissolveStartSeconds
    ? flightRange * clamp(elapsed / Math.max(flightEndSeconds, 0.0001), 0, 1)
    : preset.lifecycle.preDissolveFraction + dissolveRange * hermite01(
      clamp((elapsed - dissolveStartSeconds) / dissolveDuration, 0, 1),
      dissolveStartSlope,
      dissolveEndSlope,
    );
  const dissolveStart = Math.min(dissolveStartSeconds, duration - 0.2);
  const dissolve = smoothstep(dissolveStart, duration, elapsed);
  const startX = width * 0.14;
  const endX = width * 0.88;
  const travelX = lerp(startX, endX, flightProgress);
  const baseY = height * 0.5;
  const curveEnvelope = Math.pow(smoothstep(0.08, 0.28, progress) * (1 - smoothstep(0.72, 0.98, progress)), 0.9);
  const curveDissolve = Math.pow(1 - dissolve, 1.35);
  const fallbackCurveAmount = Math.max(48, height * 0.16);
  const curveAmount = trajectoryMode === 'curved'
    ? Math.max(preset.simulation.curveAmount, fallbackCurveAmount)
    : 0;
  const curveY = curveAmount > 0
    ? Math.sin(elapsed * preset.simulation.curveFrequency * Math.PI * 2) * curveAmount * curveEnvelope * curveDissolve
    : 0;
  const head = [travelX, baseY + curveY] as Vec2;
  const traveledLength = Math.max(0, travelX - startX);
  const maxTailLength = Math.max(width * 0.46, trail.startWidth * 4.8);
  const minTailLength = Math.max(trail.startWidth * 0.22, 6);
  const grownLength = Math.min(maxTailLength, Math.max(minTailLength * smoothstep(0, 0.08, flightProgress), traveledLength));
  const liveFactor = 1 - dissolve;
  const visibleLength = grownLength * lerp(1, 0.08, dissolve);
  const beamAlpha = liveFactor * liveFactor * (1 - dissolve * 0.48);
  const tail = [head[0] - direction[0] * visibleLength, head[1] - direction[1] * visibleLength] as Vec2;
  const center = [head[0] - direction[0] * visibleLength * 0.4, head[1] - direction[1] * visibleLength * 0.4] as Vec2;
  return { head, tail, center, length: visibleLength, progress, elapsed, flightProgress, dissolve, visibleLength, beamAlpha };
}

function buildTrailHistory(
  trail: TrailEntityConfig,
  preset: BoxUtilPreviewPreset,
  timeSeconds: number,
  width: number,
  height: number,
  direction: Vec2,
  normal: Vec2,
  track: FlightTrack,
  trajectoryMode: PreviewTrajectoryMode,
): Vec2[] {
  const fps = Math.max(preset.timeline.fps, 1);
  const frameStep = 1 / fps;
  const duration = Math.max(preset.timeline.durationSeconds, 1.2);
  const startX = width * 0.14;
  const endX = width * 0.88;
  const sampleMultiplier = Math.max(1, preset.lifecycle.historySampleMultiplier);
  const flightEndSeconds = duration * preset.lifecycle.flightEndRatio;
  const speedPxPerSecond = Math.max((endX - startX) / Math.max(flightEndSeconds, 0.0001), 1);
  const historySpanSeconds = clamp(track.visibleLength / speedPxPerSecond + frameStep * 2, frameStep * 8, duration);
  const sampleStep = frameStep / sampleMultiplier;
  const historyLength = Math.min(MAX_TRAIL_HISTORY, Math.max(20, Math.ceil(historySpanSeconds / sampleStep)));
  const history: Vec2[] = [];

  for (let i = 0; i < historyLength; i += 1) {
    const rawTime = timeSeconds - i * sampleStep;
    const sampleTime = preset.simulation.loop
      ? ((rawTime % duration) + duration) % duration
      : Math.max(0, rawTime);
    const sampleTrack = computeFlightTrack(trail, preset, sampleTime, width, height, direction, normal, trajectoryMode);
    history.push([sampleTrack.head[0], sampleTrack.head[1]]);
  }

  return smoothTrailHistory(history, preset.lifecycle.historySmoothingPasses);
}

function smoothTrailHistory(history: Vec2[], smoothingPasses: number): Vec2[] {
  if (history.length < 3) {
    return history;
  }

  let current = history;
  for (let pass = 0; pass < smoothingPasses; pass += 1) {
    const next = current.map((point, index) => {
      const p0 = current[Math.max(0, index - 2)] ?? point;
      const p1 = current[Math.max(0, index - 1)] ?? point;
      const p2 = point;
      const p3 = current[Math.min(current.length - 1, index + 1)] ?? point;
      const p4 = current[Math.min(current.length - 1, index + 2)] ?? point;
      return [
        (p0[0] + 4 * p1[0] + 6 * p2[0] + 4 * p3[0] + p4[0]) / 16,
        (p0[1] + 4 * p1[1] + 6 * p2[1] + 4 * p3[1] + p4[1]) / 16,
      ] as Vec2;
    });
    current = next;
  }

  return current;
}

function drawTravelBeam(
  ctx: CanvasRenderingContext2D,
  trail: TrailEntityConfig,
  preset: BoxUtilPreviewPreset,
  timeSeconds: number,
  track: FlightTrack,
  width: number,
  height: number,
  direction: Vec2,
  normal: Vec2,
  posHistory: Vec2[],
  visibility: PreviewOverlayLayerVisibility,
): void {
  const [hx, hy] = track.head;
  const dir = normalize(direction);
  const tailLength = track.visibleLength;
  const pulse = track.beamAlpha;
  const widthBase = projectileVfxWidthBase(trail);

  if (pulse <= 0.002 || tailLength <= 0.5) {
    return;
  }

  const centerline = buildTrailCenterline(posHistory, tailLength, 28);
  const headDirection = centerlineTangentAt(centerline, 0, dir);

  ctx.save();
  ctx.globalCompositeOperation = 'lighter';

  if (visibility.mist) drawTrailMist(ctx, preset.mistLayers, trail, widthBase, tailLength, timeSeconds, track.dissolve, pulse, centerline);
  if (visibility.glow) drawTrailGlowLayers(ctx, preset.glowLayers, trail, widthBase, tailLength, pulse, centerline);
  if (visibility.trail) drawBeamShape(ctx, trail, widthBase, tailLength, pulse, centerline);
  drawTrailDecorations(ctx, trail, widthBase, tailLength, timeSeconds, track.progress, pulse);
  if (visibility.sideWisps) drawSideWisps(ctx, preset.sideWispLayers, trail, widthBase, tailLength, pulse, centerline);

  ctx.restore();

  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  ctx.translate(hx, hy);
  ctx.rotate(Math.atan2(headDirection[1], headDirection[0]));
  if (visibility.head) drawProjectileHead(ctx, preset.headLayers, preset.lifecycle.projectileHeadSizeScale, trail, widthBase, pulse);
  ctx.restore();

  // Ribbon 装饰在世界坐标系绘制（不受 translate/rotate 影响），以便使用真实世界坐标作为噪声空间频率输入。
  ctx.save();
  ctx.globalCompositeOperation = 'screen';
  if (visibility.ribbon) drawTrailRibbonDecorations(ctx, trail, preset, timeSeconds, track, posHistory, preset.lifecycle.ribbonWaveSoftening);
  ctx.restore();
}

interface TrailPathPoint {
  position: Vec2;
  distance: number;
  t: number;
}

function buildTrailCenterline(history: Vec2[], length: number, sampleCount: number): TrailPathPoint[] {
  if (history.length === 0) {
    return [];
  }

  const histPixelsPerEntry = estimateHistoryPixelsPerEntry(history);
  const points: TrailPathPoint[] = [];
  for (let i = 0; i <= sampleCount; i += 1) {
    const t = i / Math.max(sampleCount, 1);
    const distance = length * t;
    points.push({
      position: sampleHistoryAt(history, distance, histPixelsPerEntry),
      distance,
      t,
    });
  }
  return points;
}

function estimateHistoryPixelsPerEntry(history: Vec2[]): number {
  if (history.length < 2) {
    return 4;
  }

  let totalDist = 0;
  const sampleN = Math.min(history.length - 1, 8);
  for (let k = 0; k < sampleN; k += 1) {
    const dx = history[k][0] - history[k + 1][0];
    const dy = history[k][1] - history[k + 1][1];
    totalDist += Math.sqrt(dx * dx + dy * dy);
  }
  return Math.max(0.5, totalDist / sampleN);
}

function drawTrailGlowLayers(ctx: CanvasRenderingContext2D, layers: ProjectileVfxGlowLayerConfig[], trail: TrailEntityConfig, widthBase: number, length: number, alphaScale: number, centerline: TrailPathPoint[]): void {
  const darkTail = mixRgba(trail.endColor, trail.endEmissive, 0.52);
  const hotCore = mixRgba(trail.startColor, trail.startEmissive, 0.44);
  for (const layer of layers) {
    if (!layer.enabled) continue;
    const tail = mixRgba(darkTail, hotCore, layer.colorMixTail);
    const head = layer.colorMixHead >= 1 ? [1, 1, 1, 1] as Rgba : mixRgba(trail.startColor, trail.startEmissive, layer.colorMixHead);
    drawGlowStroke(ctx, length, projectileVfxGlowLineWidth(widthBase, layer), tail, head, layer.alphaScale * alphaScale, layer.blur * alphaScale, layer.yOffset, centerline);
  }
}

function drawGlowStroke(ctx: CanvasRenderingContext2D, length: number, lineWidth: number, tail: Rgba, head: Rgba, alpha: number, blur: number, yOffset: number, centerline: TrailPathPoint[]): void {
  if (centerline.length < 2) {
    return;
  }

  const headGap = Math.max(14, lineWidth * 0.55);
  const first = centerline[Math.min(centerline.length - 1, Math.max(0, Math.floor(centerline.length * 0.72)))];
  const last = centerline[0];
  const gradient = ctx.createLinearGradient(first.position[0], first.position[1], last.position[0], last.position[1]);
  gradient.addColorStop(0, rgbaToCss(darkenRgba(tail, 0.36), 0));
  gradient.addColorStop(0.22, rgbaToCss(tail, alpha * 0.22));
  gradient.addColorStop(0.62, rgbaToCss(mixRgba(tail, head, 0.55), alpha * 0.65));
  gradient.addColorStop(0.88, rgbaToCss(head, alpha));
  gradient.addColorStop(1, rgbaToCss([1, 0.9, 0.98, 1], alpha * 0.46));

  ctx.save();
  ctx.shadowBlur = blur;
  ctx.shadowColor = rgbaToCss(head, alpha * 0.62);
  ctx.strokeStyle = gradient;
  ctx.lineWidth = Math.max(1, lineWidth);
  ctx.lineCap = 'butt';
  strokeCenterline(ctx, centerline, length * 0.72, headGap, yOffset, yOffset * 0.18);
  ctx.restore();
}

function drawBeamShape(ctx: CanvasRenderingContext2D, trail: TrailEntityConfig, widthBase: number, length: number, pulse: number, centerline: TrailPathPoint[]): void {
  if (pulse <= 0.001) {
    return;
  }
  if (centerline.length < 2) {
    return;
  }

  const bodyEmissive = mixRgba(trail.endEmissive, trail.startEmissive, 0.55);
  const bodyGlow = ctx.createLinearGradient(centerline[centerline.length - 1].position[0], centerline[centerline.length - 1].position[1], centerline[0].position[0], centerline[0].position[1]);
  for (const stop of projectileVfxBodyGradientStops(trail, pulse)) {
    bodyGlow.addColorStop(stop.offset, stop.css ?? rgbaToCss(stop.color, stop.alpha));
  }

  ctx.shadowBlur = Math.max(8, widthBase * 2.4);
  ctx.shadowColor = rgbaToCss(bodyEmissive, 0.86 * pulse);
  ctx.fillStyle = bodyGlow;
  ctx.beginPath();
  const polygon = buildCenterlineRibbonPolygon(centerline, widthBase, pulse);
  ctx.moveTo(polygon[0][0], polygon[0][1]);
  for (const point of polygon.slice(1)) ctx.lineTo(point[0], point[1]);
  ctx.closePath();
  ctx.fill();
}

function drawProjectileHead(ctx: CanvasRenderingContext2D, layers: ProjectileVfxHeadLayerConfig[], headSizeScale: number, trail: TrailEntityConfig, widthBase: number, pulse: number): void {
  const enabledLayers = layers.filter((layer) => layer.enabled);
  if (enabledLayers.length === 0) return;

  const firstLayout = projectileVfxHeadFillLayout(trail, enabledLayers[0], headSizeScale, widthBase, pulse);
  if (firstLayout.headVisible <= 0.01) {
    return;
  }

  ctx.save();
  ctx.globalCompositeOperation = 'lighter';
  for (const layer of enabledLayers) {
    const layout = projectileVfxHeadFillLayout(trail, layer, headSizeScale, widthBase, pulse);
    ctx.filter = `blur(${layer.blur}px)`;
    ctx.shadowBlur = Math.max(8, widthBase * 2.8) * layout.headVisible;
    ctx.shadowColor = rgbaToCss(layout.colors.mid, 0.84 * layout.alpha);
    const shell = ctx.createLinearGradient(layout.rearX, 0, 0, 0);
    shell.addColorStop(0, rgbaToCss(layout.colors.start, layout.colors.start[3] * layout.alpha));
    shell.addColorStop(0.36, rgbaToCss(layout.colors.mid, layout.colors.mid[3] * layout.alpha));
    shell.addColorStop(0.74, rgbaToCss(layout.colors.end, 0.9 * layout.alpha));
    shell.addColorStop(1, rgbaToCss([1, 1, 1, 1], 0.98 * layout.alpha));
    ctx.fillStyle = shell;
    ctx.lineJoin = 'round';
    ctx.beginPath();
    const { vertices } = layout;
    ctx.moveTo(vertices.rearTop[0], vertices.rearTop[1]);
    ctx.lineTo(vertices.shoulderTop[0], vertices.shoulderTop[1]);
    ctx.quadraticCurveTo(vertices.curveTop[0], vertices.curveTop[1], vertices.tip[0], vertices.tip[1]);
    ctx.quadraticCurveTo(vertices.curveBottom[0], vertices.curveBottom[1], vertices.shoulderBottom[0], vertices.shoulderBottom[1]);
    ctx.lineTo(vertices.rearBottom[0], vertices.rearBottom[1]);
    ctx.closePath();
    ctx.fill();
  }

  ctx.restore();
}

function drawTrailMist(ctx: CanvasRenderingContext2D, layers: ProjectileVfxMistLayerConfig[], trail: TrailEntityConfig, widthBase: number, length: number, timeSeconds: number, dissolve: number, pulse: number, centerline: TrailPathPoint[]): void {
  const alphaScale = pulse * (1 - dissolve * 0.72);
  if (alphaScale <= 0.004) {
    return;
  }

  const enabledLayers = layers.filter((layer) => layer.enabled);
  if (enabledLayers.length === 0) return;

  ctx.save();
  ctx.globalCompositeOperation = 'screen';

  for (const layer of enabledLayers) {
    const blobs = Math.max(0, Math.round(layer.blobCount));
    for (let i = 0; i < blobs; i += 1) {
      const seed = i * 13.71;
      const t = (i + shaderNoise(seed, timeSeconds * 0.17)) / Math.max(blobs, 1);
      const base = sampleCenterlineByRatio(centerline, t * layer.lengthScale);
      const envelope = Math.sin(Math.PI * clamp(t, 0, 1));
      const noise = layeredNoise(t * layer.noiseScale - timeSeconds * layer.driftSpeed, seed * 0.017);
      const normal = centerlineNormalAt(centerline, t);
      const y = (shaderNoise(seed, 8.4) - 0.5) * widthBase * 5.4 * layer.widthScale * envelope;
      const x = base.position[0] + normal[0] * y;
      const cy = base.position[1] + normal[1] * y;
      const rx = widthBase * lerp(layer.rxRange.min, layer.rxRange.max, noise) * (0.3 + envelope);
      const ry = widthBase * lerp(layer.ryRange.min, layer.ryRange.max, shaderNoise(seed, 12.2)) * (0.4 + envelope * 0.7);
      const color = mixRgba(layer.colorStart, layer.colorEnd, clamp(1 - t * 0.62, 0, 1));
      const alpha = alphaScale * lerp(layer.alphaRange.min, layer.alphaRange.max, noise) * envelope;
      const grad = ctx.createRadialGradient(x, cy, 0, x, cy, Math.max(rx, ry));
      grad.addColorStop(0, rgbaToCss(color, alpha));
      grad.addColorStop(0.58, rgbaToCss(color, alpha * 0.28));
      grad.addColorStop(1, rgbaToCss(color, 0));
      ctx.fillStyle = grad;
      ctx.save();
      ctx.translate(x, cy);
      ctx.scale(rx / Math.max(rx, ry), ry / Math.max(rx, ry));
      ctx.beginPath();
      ctx.arc(0, 0, Math.max(rx, ry), 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }
  }

  ctx.restore();
}

function drawTrailDecorations(
  ctx: CanvasRenderingContext2D,
  trail: TrailEntityConfig,
  widthBase: number,
  length: number,
  timeSeconds: number,
  progress: number,
  pulse: number,
): void {
  void ctx;
  void trail;
  void widthBase;
  void length;
  void timeSeconds;
  void progress;
  void pulse;
}

function drawTrailRibbonDecorations(
  ctx: CanvasRenderingContext2D,
  trail: TrailEntityConfig,
  preset: BoxUtilPreviewPreset,
  timeSeconds: number,
  track: FlightTrack,
  posHistory: Vec2[],
  ribbonWaveSoftening: number,
): void {
  const ribbons = preset.ribbonDecorations.length > 0 ? preset.ribbonDecorations : trail.ribbonDecorations;
  if (!ribbons?.length) {
    return;
  }

  ctx.save();
  ctx.globalCompositeOperation = 'source-over';

  for (const ribbon of ribbons) {
    if (!ribbon.enabled) {
      continue;
    }

    const sampleCount = ribbon.renderMode === 'byNodeCount'
      ? Math.max(8, Math.round(trail.nodes.length * ribbon.nodeCountScale))
      : Math.max(8, Math.round(track.visibleLength * ribbon.lengthScale / 8));

    drawRibbonDecoration(ctx, trail, ribbon, timeSeconds, track, sampleCount, posHistory, ribbonWaveSoftening);
  }

  ctx.restore();
}

/**
 * 从位置历史中按目标距离（从弹头向后）插值得到世界坐标。
 * history[0] = 当前弹头，history[n] = n 帧前的位置。
 * histPixelsPerEntry：每两个相邻历史条目之间的近似像素距离。
 */
function sampleHistoryAt(history: Vec2[], targetDist: number, histPixelsPerEntry: number): Vec2 {
  if (history.length === 0) {
    return [0, 0];
  }
  if (targetDist <= 0 || history.length === 1) {
    return history[0];
  }
  // 将距离转换为历史索引（浮点数）
  const rawIdx = targetDist / Math.max(histPixelsPerEntry, 0.1);
  const idx0 = Math.floor(rawIdx);
  const idx1 = idx0 + 1;
  if (idx0 >= history.length - 1) {
    return history[history.length - 1];
  }
  const frac = rawIdx - idx0;
  const p0 = history[idx0];
  const p1 = history[idx1];
  return [lerp(p0[0], p1[0], frac), lerp(p0[1], p1[1], frac)];
}

function drawRibbonDecoration(
  ctx: CanvasRenderingContext2D,
  trail: TrailEntityConfig,
  ribbon: TrailRibbonDecorationConfig,
  timeSeconds: number,
  track: FlightTrack,
  sampleCount: number,
  posHistory: Vec2[],
  ribbonWaveSoftening: number,
): void {
  const baseColor = ribbon.color;
  const gradientStops = ribbon.colorGradient.enabled && ribbon.colorGradient.stops.length > 0
    ? ribbon.colorGradient.stops.slice().sort((a, b) => a.offset - b.offset)
    : [];
  const ribbonStart = ribbon.startColor;
  const ribbonEnd = ribbon.endColor;
  const widthBase = Math.max(0.65, trail.startWidth * ribbon.thickness);

  ctx.save();
  ctx.shadowBlur = ribbon.blur;
  ctx.shadowColor = rgbaToCss(baseColor, ribbon.alphaScale);
  ctx.lineCap = 'butt';
  ctx.lineJoin = 'round';

  // 估算历史条目之间的像素间距（用于从位置历史插值）
  // 以最近几帧的平均运动速度为基准
  let histPixelsPerEntry = 4;
  if (posHistory.length >= 2) {
    let totalDist = 0;
    const sampleN = Math.min(posHistory.length - 1, 8);
    for (let k = 0; k < sampleN; k += 1) {
      const dx = posHistory[k][0] - posHistory[k + 1][0];
      const dy = posHistory[k][1] - posHistory[k + 1][1];
      totalDist += Math.sqrt(dx * dx + dy * dy);
    }
    histPixelsPerEntry = Math.max(0.5, totalDist / sampleN);
  }

  const pathPoints: Array<{ position: Vec2; t: number; color: Rgba; alpha: number; lineWidth: number }> = [];

  for (let i = 0; i <= sampleCount; i += 1) {
    const t = i / sampleCount;
    const lengthProgress = ribbon.renderMode === 'byLength' ? t : t * ribbon.lengthScale;
    const distFromHead = track.visibleLength * clamp(lengthProgress, 0, 1) + ribbon.startOffset;

    // 从历史得到该点的世界坐标（弯曲轨迹支持）
    const nodeWorld = sampleHistoryAt(posHistory, distFromHead, histPixelsPerEntry);
    const wx0 = nodeWorld[0];
    const wy0 = nodeWorld[1];

    // 计算该点的切线方向（用于垂直偏移）
    const nextDist = distFromHead + track.visibleLength / sampleCount;
    const prevNode = sampleHistoryAt(posHistory, distFromHead - track.visibleLength / sampleCount, histPixelsPerEntry);
    const nextNode = sampleHistoryAt(posHistory, nextDist, histPixelsPerEntry);
    const tanX = prevNode[0] - nextNode[0];
    const tanY = prevNode[1] - nextNode[1];
    const tanLen = Math.sqrt(tanX * tanX + tanY * tanY) || 1;
    // 法向量（垂直于切线，指向"上"方）
    const perpX = -tanY / tanLen;
    const perpY = tanX / tanLen;

    // 噪声/波形使用世界 X 作为空间坐标，实现"原地动画"效果
    const worldX = wx0;
    const worldTimePhase = timeSeconds * ribbon.waveSpeed * 0.18 + trail.flickerSyncCode * 0.05;
    const smokeEnvelope = smoothstep(0.08, 0.28, t) * (1 - smoothstep(0.7, 0.96, t));
    let wave: number;
    if (ribbon.waveType === 'noise') {
      const noiseVal = layeredNoise(worldX * ribbon.noiseScale * 0.005, worldTimePhase);
      const easedNoise = smoothstep(0.12, 0.88, noiseVal);
      wave = (easedNoise - 0.5) * 2 * ribbon.waveAmplitude * ribbonWaveSoftening * lerp(0.62, 1, smokeEnvelope);
    } else if (ribbon.waveType === 'zigzag') {
      const zigzagPhase = worldX * ribbon.waveFrequency * 0.01 + timeSeconds * ribbon.waveSpeed;
      const zigzagRaw = 1 - 4 * Math.abs(fract(zigzagPhase + 0.25) - 0.5);
      const easedZigzag = zigzagRaw >= 0
        ? smoothstep(0, 1, zigzagRaw)
        : -smoothstep(0, 1, -zigzagRaw);
      wave = easedZigzag * ribbon.waveAmplitude * ribbonWaveSoftening * lerp(0.68, 1, smokeEnvelope);
    } else {
      // sine
      const sinePhase = worldX * ribbon.waveFrequency * 0.01 + timeSeconds * ribbon.waveSpeed;
      wave = Math.sin(sinePhase * Math.PI * 2 + trail.flickerSyncCode * 0.05) * ribbon.waveAmplitude * ribbonWaveSoftening * lerp(0.72, 1, smokeEnvelope);
    }

    // 将垂直偏移（endOffset + wave）沿法线方向施加
    const perpOffset = (ribbon.endOffset + wave * widthBase) * lerp(0.72, 1, smokeEnvelope);
    const wx = wx0 + perpX * perpOffset;
    const wy = wy0 + perpY * perpOffset;

    const color = gradientStops.length > 0 ? sampleTrailDecorationGradient(gradientStops, t, baseColor) : lerpRgba(ribbonStart, ribbonEnd, t);
    const alpha = ribbon.alphaScale * (1 - t * 0.22) * lerp(0.6, 1, smokeEnvelope);
    pathPoints.push({ position: [wx, wy], t, color, alpha, lineWidth: Math.max(0.5, widthBase * (1 - t * 0.24)) });
  }

  drawRibbonPath(ctx, pathPoints, ribbon, baseColor, widthBase);

  ctx.restore();
}

function drawRibbonPath(
  ctx: CanvasRenderingContext2D,
  pathPoints: Array<{ position: Vec2; t: number; color: Rgba; alpha: number; lineWidth: number }>,
  ribbon: TrailRibbonDecorationConfig,
  baseColor: Rgba,
  widthBase: number,
): void {
  if (pathPoints.length < 2) {
    return;
  }

  const first = pathPoints[0];
  const last = pathPoints[pathPoints.length - 1];
  const gradient = ctx.createLinearGradient(first.position[0], first.position[1], last.position[0], last.position[1]);
  for (const point of pathPoints) {
    gradient.addColorStop(clamp(point.t, 0, 1), rgbaToCss(point.color, point.alpha));
  }

  ctx.strokeStyle = gradient;
  ctx.lineWidth = Math.max(0.5, averageRibbonLineWidth(pathPoints));
  ctx.beginPath();
  ctx.moveTo(first.position[0], first.position[1]);
  for (let i = 1; i < pathPoints.length - 1; i += 1) {
    const curr = pathPoints[i];
    const next = pathPoints[i + 1];
    const midX = (curr.position[0] + next.position[0]) * 0.5;
    const midY = (curr.position[1] + next.position[1]) * 0.5;
    ctx.quadraticCurveTo(curr.position[0], curr.position[1], midX, midY);
  }
  ctx.lineTo(last.position[0], last.position[1]);
  ctx.stroke();

  if (ribbon.blur > 0) {
    ctx.save();
    ctx.shadowBlur = 0;
    ctx.strokeStyle = rgbaToCss(baseColor, ribbon.alphaScale * 0.18);
    ctx.lineWidth = Math.max(0.5, widthBase * 0.38);
    ctx.stroke();
    ctx.restore();
  }
}

function averageRibbonLineWidth(pathPoints: Array<{ lineWidth: number }>): number {
  return pathPoints.reduce((sum, point) => sum + point.lineWidth, 0) / pathPoints.length;
}

function sampleTrailDecorationGradient(stops: { offset: number; color: Rgba }[], t: number, fallback: Rgba): Rgba {
  if (stops.length === 0) {
    return fallback;
  }

  if (t <= stops[0].offset) {
    return stops[0].color;
  }

  for (let i = 0; i < stops.length - 1; i += 1) {
    const left = stops[i];
    const right = stops[i + 1];
    if (t >= left.offset && t <= right.offset) {
      const ratio = (t - left.offset) / Math.max(right.offset - left.offset, 0.0001);
      return lerpRgba(left.color, right.color, ratio);
    }
  }

  return stops[stops.length - 1].color;
}

function buildCenterlineRibbonPolygon(centerline: TrailPathPoint[], widthBase: number, pulse: number): Vec2[] {
  const top: Vec2[] = [];
  const bottom: Vec2[] = [];
  for (const point of centerline) {
    const normal = centerlineNormalAt(centerline, point.t);
    const halfWidth = bodyHalfWidthAt(point.t, widthBase, pulse);
    top.push([point.position[0] + normal[0] * halfWidth, point.position[1] + normal[1] * halfWidth]);
    bottom.push([point.position[0] - normal[0] * halfWidth, point.position[1] - normal[1] * halfWidth]);
  }
  return [...top.reverse(), ...bottom];
}

function bodyHalfWidthAt(t: number, widthBase: number, pulse: number): number {
  const tailWidth = Math.max(1.0, widthBase * 0.72);
  const headVisible = smoothstep(0.28, 0.82, pulse);
  const projectileWidth = Math.max(4.8, widthBase * 1.72) * headVisible;
  const shaped = smoothstep(0.05, 0.42, 1 - t) * (1 - smoothstep(0.92, 1, t) * 0.72);
  return lerp(projectileWidth * 0.56, tailWidth * 0.12, t) * (0.52 + shaped * 0.48);
}

function strokeCenterline(ctx: CanvasRenderingContext2D, centerline: TrailPathPoint[], maxDistance: number, headGap: number, tailOffset: number, headOffset: number): void {
  const points = centerline
    .filter((point) => point.distance <= maxDistance && point.distance >= headGap)
    .map((point) => offsetCenterlinePoint(centerline, point.distance, lerp(headOffset, tailOffset, clamp(point.distance / Math.max(maxDistance, 0.0001), 0, 1))));
  if (points.length < 2) {
    return;
  }

  ctx.beginPath();
  ctx.moveTo(points[points.length - 1][0], points[points.length - 1][1]);
  for (let i = points.length - 2; i >= 0; i -= 1) {
    ctx.lineTo(points[i][0], points[i][1]);
  }
  ctx.stroke();
}

function sampleCenterlineByRatio(centerline: TrailPathPoint[], ratio: number): TrailPathPoint {
  if (centerline.length === 0) {
    return { position: [0, 0], distance: 0, t: 0 };
  }
  const target = clamp(ratio, 0, 1);
  let best = centerline[0];
  for (const point of centerline) {
    if (Math.abs(point.t - target) < Math.abs(best.t - target)) {
      best = point;
    }
  }
  return best;
}

function offsetCenterlinePoint(centerline: TrailPathPoint[], distance: number, offset: number): Vec2 {
  if (centerline.length === 0) {
    return [0, 0];
  }
  const length = centerline[centerline.length - 1].distance || 1;
  const ratio = clamp(distance / length, 0, 1);
  const point = sampleCenterlineByRatio(centerline, ratio);
  const normal = centerlineNormalAt(centerline, ratio);
  return [point.position[0] + normal[0] * offset, point.position[1] + normal[1] * offset];
}

function centerlineNormalAt(centerline: TrailPathPoint[], ratio: number): Vec2 {
  const tangent = centerlineTangentAt(centerline, ratio, [1, 0]);
  return [-tangent[1], tangent[0]];
}

function centerlineTangentAt(centerline: TrailPathPoint[], ratio: number, fallback: Vec2): Vec2 {
  if (centerline.length < 2) {
    return normalize(fallback);
  }
  const index = Math.round(clamp(ratio, 0, 1) * (centerline.length - 1));
  const previous = centerline[Math.max(0, index - 1)].position;
  const next = centerline[Math.min(centerline.length - 1, index + 1)].position;
  const tangent = normalize([previous[0] - next[0], previous[1] - next[1]]);
  if (!Number.isFinite(tangent[0]) || !Number.isFinite(tangent[1])) {
    return normalize(fallback);
  }
  return tangent;
}

function drawSideWisps(ctx: CanvasRenderingContext2D, layers: ProjectileVfxSideWispLayerConfig[], trail: TrailEntityConfig, widthBase: number, length: number, alphaScale: number, centerline: TrailPathPoint[]): void {
  const enabledLayers = layers.filter((layer) => layer.enabled);
  for (const layer of enabledLayers) {
    const sideColor = layer.color ?? mixRgba(trail.endEmissive, trail.startColor, 0.36);
    for (const localPath of projectileVfxSideWispLocalPaths(layer, length, widthBase)) {
      const points = buildSideWispCenterlinePath(centerline, localPath, widthBase);
      if (points.length < 2) {
        continue;
      }
      const start = points[0];
      const end = points[points.length - 1];
      const mid = points[Math.floor(points.length * 0.5)];
      const gradient = ctx.createLinearGradient(start[0], start[1], end[0], end[1]);
      gradient.addColorStop(0, 'rgba(0,0,0,0)');
      gradient.addColorStop(0.28, rgbaToCss(darkenRgba(sideColor, 0.5), 0.1 * alphaScale));
      gradient.addColorStop(0.7, rgbaToCss(sideColor, layer.alphaScale * alphaScale));
      gradient.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.save();
      ctx.shadowBlur = layer.blur;
      ctx.shadowColor = rgbaToCss(sideColor, 0.28 * alphaScale);
      ctx.strokeStyle = gradient;
      ctx.lineWidth = Math.max(0.65, widthBase * layer.widthScale);
      ctx.lineCap = 'round';
      ctx.beginPath();
      ctx.moveTo(start[0], start[1]);
      for (let i = 1; i < points.length - 1; i += 1) {
        const current = points[i];
        const next = points[i + 1];
        const midX = (current[0] + next[0]) * 0.5;
        const midY = (current[1] + next[1]) * 0.5;
        ctx.quadraticCurveTo(current[0], current[1], midX, midY);
      }
      ctx.lineTo(end[0], end[1]);
      ctx.stroke();
      ctx.restore();
    }
  }
}

function buildSideWispCenterlinePath(centerline: TrailPathPoint[], localPath: Vec2[], widthBase: number): Vec2[] {
  if (centerline.length < 2 || localPath.length < 2) {
    return [];
  }

  const startDistance = Math.max(...localPath.map((point) => Math.abs(point[0])));
  const endDistance = Math.min(...localPath.map((point) => Math.abs(point[0])));
  const headGap = Math.max(widthBase * 6.5, 18);
  const clampedEnd = Math.max(endDistance, headGap);
  const distanceSpan = Math.max(0, startDistance - clampedEnd);
  if (distanceSpan <= 0.5) {
    return [];
  }

  const sampleCount = Math.max(6, Math.ceil(distanceSpan / Math.max(12, widthBase * 2.4)));
  const offsets = localPath
    .map((point) => ({ distance: Math.abs(point[0]), offset: point[1] }))
    .sort((a, b) => b.distance - a.distance);

  const points: Vec2[] = [];
  for (let i = 0; i <= sampleCount; i += 1) {
    const t = i / sampleCount;
    const distance = lerp(startDistance, clampedEnd, t);
    const offset = sampleSideWispOffset(offsets, distance);
    points.push(offsetCenterlinePoint(centerline, distance, offset));
  }
  return points;
}

function sampleSideWispOffset(samples: Array<{ distance: number; offset: number }>, distance: number): number {
  if (samples.length === 0) {
    return 0;
  }
  if (distance >= samples[0].distance) {
    return samples[0].offset;
  }
  for (let i = 0; i < samples.length - 1; i += 1) {
    const left = samples[i];
    const right = samples[i + 1];
    if (distance <= left.distance && distance >= right.distance) {
      const ratio = (left.distance - distance) / Math.max(left.distance - right.distance, 0.0001);
      return lerp(left.offset, right.offset, ratio);
    }
  }
  return samples[samples.length - 1].offset;
}

function lerp(start: number, end: number, t: number): number {
  return start + (end - start) * t;
}

function lerpRgba(start: Rgba, end: Rgba, t: number): Rgba {
  return [lerp(start[0], end[0], t), lerp(start[1], end[1], t), lerp(start[2], end[2], t), lerp(start[3], end[3], t)];
}

function mixRgba(start: Rgba, end: Rgba, t: number): Rgba {
  return lerpRgba(start, end, t);
}

function normalize(vec: Vec2): Vec2 {
  const length = Math.hypot(vec[0], vec[1]) || 1;
  return [vec[0] / length, vec[1] / length];
}

function smoothstep(edge0: number, edge1: number, x: number): number {
  const t = clamp((x - edge0) / Math.max(edge1 - edge0, 0.0001), 0, 1);
  return t * t * (3 - 2 * t);
}

function hermite01(t: number, m0: number, m1: number): number {
  const u = clamp(t, 0, 1);
  const u2 = u * u;
  const u3 = u2 * u;
  return (2 * u3 - 3 * u2 + 1) * 0 + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * 1 + (u3 - u2) * m1;
}

function darkenRgba(color: Rgba, factor: number): Rgba {
  return [color[0] * factor, color[1] * factor, color[2] * factor, color[3]];
}

function rgbaToCss(color: Rgba, alphaOverride?: number): string {
  const alpha = alphaOverride ?? color[3];
  return `rgba(${Math.round(clamp(color[0], 0, 1) * 255)}, ${Math.round(clamp(color[1], 0, 1) * 255)}, ${Math.round(clamp(color[2], 0, 1) * 255)}, ${clamp(alpha, 0, 1)})`;
}

function shaderNoise(x: number, y: number): number {
  return fract(Math.sin(x * 127.1 + y * 311.7) * 43758.5453123);
}

function layeredNoise(x: number, y: number): number {
  return (
    shaderNoise(x, y) * 0.52 +
    shaderNoise(x * 2.13 + 17.4, y * 2.31 - 9.2) * 0.32 +
    shaderNoise(x * 4.07 - 3.8, y * 3.63 + 21.6) * 0.16
  );
}

function fract(value: number): number {
  return value - Math.floor(value);
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

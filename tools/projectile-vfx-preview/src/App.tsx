import { useEffect, useReducer, useState } from 'react';
import { createDefaultPreset } from './model/preset';
import { DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY, PreviewOverlayLayerVisibility } from './render/previewOverlayRenderer';
import { createInitialTimelineState, timelineReducer } from './sim/timeline';
import { ConfigPanel } from './ui/ConfigPanel';
import { EntityInspector } from './ui/EntityInspector';
import { PreviewCanvas } from './ui/PreviewCanvas';
import { TimelineControls } from './ui/TimelineControls';
import { VersionCompare } from './ui/VersionCompare';
import { captureCanvasPng } from './ui/capture';

export default function App() {
  const [preset, setPreset] = useState(() => loadPreset());
  const [canvas, setCanvas] = useState<HTMLCanvasElement | null>(null);
  const [layerVisibility, setLayerVisibility] = useState<PreviewOverlayLayerVisibility>(DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY);
  const [timeline, dispatchTimeline] = useReducer(timelineReducer, createInitialTimelineState(preset.timeline));

  useEffect(() => {
    setStorageItem('astd-projectile-vfx-preset', JSON.stringify(preset));
  }, [preset]);

  useEffect(() => {
    dispatchTimeline({ type: 'sync', fps: preset.timeline.fps, durationSeconds: preset.timeline.durationSeconds });
  }, [preset.timeline.durationSeconds, preset.timeline.fps]);

  useEffect(() => {
    if (!timeline.playing) {
      return;
    }

    let lastTime = performance.now();
    let frame = 0;
    const tick = (now: number) => {
      const deltaSeconds = Math.min(0.05, (now - lastTime) / 1000);
      lastTime = now;
      dispatchTimeline({ type: 'tick', deltaSeconds, loop: preset.simulation.loop });
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [timeline.playing, preset.simulation.loop]);

  return (
    <main className="app-shell">
      <header className="app-header">
        <div>
          <h1>ASTD Projectile VFX Preview</h1>
          <p>{preset.name}</p>
        </div>
        <div className="header-metrics">
          <span>{preset.trailEntities.length} trail</span>
        </div>
      </header>
      <section className="preview-stage">
        <PreviewCanvas preset={preset} timeSeconds={timeline.timeSeconds} onCanvasReady={setCanvas} layerVisibility={layerVisibility} />
        <button
          type="button"
          className="capture-button"
          onClick={() => {
            if (canvas) {
              window.open(captureCanvasPng(canvas), '_blank');
            }
          }}
        >
          Screenshot PNG
        </button>
      </section>
      <ConfigPanel preset={preset} onPresetChange={setPreset} layerVisibility={layerVisibility} onLayerVisibilityChange={setLayerVisibility} />
      <div className="right-panel-stack">
        <EntityInspector preset={preset} onPresetChange={setPreset} />
        <VersionCompare preset={preset} onPresetChange={setPreset} />
      </div>
      <TimelineControls
        state={timeline}
        onPlayPause={() => dispatchTimeline({ type: 'toggle' })}
        onStepBackward={() => dispatchTimeline({ type: 'stepBackward' })}
        onStepForward={() => dispatchTimeline({ type: 'stepForward' })}
        onSeek={(timeSeconds) => dispatchTimeline({ type: 'seek', timeSeconds })}
      />
    </main>
  );
}

function loadPreset() {
  const cached = getStorageItem('astd-projectile-vfx-preset');
  if (!cached) {
    return createDefaultPreset();
  }

  try {
    const parsed = JSON.parse(cached) as Partial<ReturnType<typeof createDefaultPreset>>;
    const defaults = createDefaultPreset();
    return {
      ...defaults,
      ...parsed,
      trailEntities: mergeTrailEntities(parsed.trailEntities ?? defaults.trailEntities, defaults.trailEntities),
      headLayers: mergeArrayByIndex(parsed.headLayers, defaults.headLayers),
      glowLayers: mergeArrayByIndex(parsed.glowLayers, defaults.glowLayers),
      mistLayers: mergeArrayByIndex(parsed.mistLayers, defaults.mistLayers),
      sideWispLayers: mergeArrayByIndex(parsed.sideWispLayers, defaults.sideWispLayers),
      ribbonDecorations: mergeArrayByIndex(parsed.ribbonDecorations, defaults.ribbonDecorations),
      lifecycle: { ...defaults.lifecycle, ...parsed.lifecycle },
      samplingPolicy: { ...defaults.samplingPolicy, ...parsed.samplingPolicy },
      timeline: { ...defaults.timeline, ...parsed.timeline },
      previewCamera: { ...defaults.previewCamera, ...parsed.previewCamera },
      simulation: { ...defaults.simulation, ...parsed.simulation },
    };
  } catch {
    return createDefaultPreset();
  }
}

function mergeTrailEntities(
  source: ReturnType<typeof createDefaultPreset>['trailEntities'],
  defaults: ReturnType<typeof createDefaultPreset>['trailEntities'],
) {
  return source.map((trail, index) => ({
    ...defaults[index % defaults.length],
    ...trail,
    ribbonDecorations: (trail.ribbonDecorations ?? defaults[index % defaults.length].ribbonDecorations).map((ribbon, ribbonIndex) => ({
      ...defaults[index % defaults.length].ribbonDecorations[ribbonIndex % defaults[index % defaults.length].ribbonDecorations.length],
      ...ribbon,
      startColor: ribbon.startColor ?? defaults[index % defaults.length].ribbonDecorations[ribbonIndex % defaults[index % defaults.length].ribbonDecorations.length].startColor,
      endColor: ribbon.endColor ?? defaults[index % defaults.length].ribbonDecorations[ribbonIndex % defaults[index % defaults.length].ribbonDecorations.length].endColor,
      color: ribbon.color ?? defaults[index % defaults.length].ribbonDecorations[ribbonIndex % defaults[index % defaults.length].ribbonDecorations.length].color,
      colorGradient: {
        ...defaults[index % defaults.length].ribbonDecorations[ribbonIndex % defaults[index % defaults.length].ribbonDecorations.length].colorGradient,
        ...ribbon.colorGradient,
      },
    })),
  }));
}

function mergeArrayByIndex<T>(source: T[] | undefined, defaults: T[]): T[] {
  if (!Array.isArray(source)) {
    return defaults;
  }

  return source.map((item, index) => ({
    ...defaults[index % defaults.length],
    ...item,
  }));
}

function getStorageItem(key: string): string | null {
  const storage = globalThis.localStorage;
  if (!storage || typeof storage.getItem !== 'function') {
    return null;
  }

  try {
    return storage.getItem(key);
  } catch {
    return null;
  }
}

function setStorageItem(key: string, value: string): void {
  const storage = globalThis.localStorage;
  if (!storage || typeof storage.setItem !== 'function') {
    return;
  }

  try {
    storage.setItem(key, value);
  } catch {
    // ignore storage failures
  }
}

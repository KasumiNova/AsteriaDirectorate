import { useMemo, useState } from 'react';
import { cloneLayer, controlPointsForMode, createDefaultBeamPreset, type BeamLayer, type BeamMode, type BeamPreset } from '../model/beamPreset';
import { exportKotlinBeamDraftPreset } from '../export/kotlinBeamExport';
import { BeamPreviewCanvas } from './BeamPreviewCanvas';

interface RenderPreviewSummary {
  enabledLayerCount: number;
  sampleCount: number;
}

interface BeamEditorProps {
  renderPreview?: (summary: RenderPreviewSummary) => void;
}

export function BeamEditor({ renderPreview }: BeamEditorProps) {
  const [preset, setPreset] = useState<BeamPreset>(() => createDefaultBeamPreset());
  const [selectedLayerId, setSelectedLayerId] = useState(preset.layers[0].id);
  const selectedLayer = preset.layers.find((layer) => layer.id === selectedLayerId) ?? preset.layers[0];
  const exportText = useMemo(() => exportKotlinBeamDraftPreset(preset), [preset]);

  const updatePreset = (next: BeamPreset) => {
    setPreset(next);
    renderPreview?.({ enabledLayerCount: next.layers.filter((layer) => layer.enabled).length, sampleCount: next.quality });
  };

  const setMode = (mode: BeamMode) => updatePreset({ ...preset, mode, controlPoints: controlPointsForMode(preset, mode) });
  const updateLayer = (id: string, patch: Partial<BeamLayer>) => {
    updatePreset({ ...preset, layers: preset.layers.map((layer) => (layer.id === id ? { ...layer, ...patch } : layer)) });
  };

  const addLayer = () => {
    const base = preset.layers[preset.layers.length - 1] ?? createDefaultBeamPreset().layers[0];
    const layer = cloneLayer(base, `${preset.layers.length + 1}`);
    updatePreset({ ...preset, layers: [...preset.layers, layer] });
    setSelectedLayerId(layer.id);
  };

  const duplicateLayer = () => {
    const layer = cloneLayer(selectedLayer, `${preset.layers.length + 1}`);
    updatePreset({ ...preset, layers: [...preset.layers, layer] });
    setSelectedLayerId(layer.id);
  };

  const deleteLayer = () => {
    if (preset.layers.length <= 1) {
      return;
    }
    const nextLayers = preset.layers.filter((layer) => layer.id !== selectedLayer.id);
    updatePreset({ ...preset, layers: nextLayers });
    setSelectedLayerId(nextLayers[0].id);
  };

  const moveLayer = (direction: -1 | 1) => {
    const index = preset.layers.findIndex((layer) => layer.id === selectedLayer.id);
    const nextIndex = index + direction;
    if (nextIndex < 0 || nextIndex >= preset.layers.length) {
      return;
    }
    const layers = [...preset.layers];
    const [layer] = layers.splice(index, 1);
    layers.splice(nextIndex, 0, layer);
    updatePreset({ ...preset, layers });
  };

  const updatePoint = (index: number, axis: 'x' | 'y', value: number) => {
    updatePreset({
      ...preset,
      controlPoints: preset.controlPoints.map((point, pointIndex) => (pointIndex === index ? { ...point, [axis]: value } : point)),
    });
  };

  return (
    <main className="app-shell">
      <section className="preview-pane">
        <BeamPreviewCanvas preset={preset} onRender={renderPreview} />
      </section>

      <aside className="editor-pane">
        <div className="panel-row">
          <h1>Beam VFX</h1>
          <output aria-label="Beam mode">{preset.mode === 'straight' ? 'Straight' : 'Curved'}</output>
        </div>

        <div className="segmented-control">
          <button type="button" className={preset.mode === 'straight' ? 'active' : ''} onClick={() => setMode('straight')}>
            Straight
          </button>
          <button type="button" className={preset.mode === 'curved' ? 'active' : ''} onClick={() => setMode('curved')}>
            Curved
          </button>
        </div>

        <section className="control-section">
          <h2>Control Points</h2>
          <div className="point-grid">
            {preset.controlPoints.map((point, index) => (
              <fieldset key={index}>
                <legend>{pointLabel(index, preset.mode)}</legend>
                <label>
                  X
                  <input type="number" value={point.x} onChange={(event) => updatePoint(index, 'x', Number(event.target.value))} />
                </label>
                <label>
                  Y
                  <input type="number" value={point.y} onChange={(event) => updatePoint(index, 'y', Number(event.target.value))} />
                </label>
              </fieldset>
            ))}
          </div>
        </section>

        <section className="control-section">
          <div className="panel-row">
            <h2>Layers</h2>
            <span data-testid="layer-count">{preset.layers.length} layers</span>
          </div>
          <div className="layer-actions">
            <button type="button" onClick={addLayer}>Add layer</button>
            <button type="button" onClick={duplicateLayer}>Duplicate</button>
            <button type="button" onClick={deleteLayer}>Delete</button>
            <button type="button" onClick={() => moveLayer(-1)}>Up</button>
            <button type="button" onClick={() => moveLayer(1)}>Down</button>
          </div>
          <div className="layer-stack">
            {preset.layers.map((layer) => (
              <button
                key={layer.id}
                type="button"
                className={layer.id === selectedLayer.id ? 'selected layer-row' : 'layer-row'}
                onClick={() => setSelectedLayerId(layer.id)}
              >
                <span>{layer.name}</span>
                <span>{layer.enabled ? 'On' : 'Off'}</span>
              </button>
            ))}
          </div>
        </section>

        {selectedLayer && (
          <section className="control-section layer-editor">
            <h2>{selectedLayer.name}</h2>
            <label className="checkbox-row">
              <input
                type="checkbox"
                aria-label={`${selectedLayer.name} Enabled`}
                checked={selectedLayer.enabled}
                onChange={(event) => updateLayer(selectedLayer.id, { enabled: event.target.checked })}
              />
              Enabled
            </label>
            <label>
              Name
              <input value={selectedLayer.name} onChange={(event) => updateLayer(selectedLayer.id, { name: event.target.value })} />
            </label>
            <label>
              Width start
              <input type="number" value={selectedLayer.widthStart} onChange={(event) => updateLayer(selectedLayer.id, { widthStart: Number(event.target.value) })} />
            </label>
            <label>
              Width end
              <input type="number" value={selectedLayer.widthEnd} onChange={(event) => updateLayer(selectedLayer.id, { widthEnd: Number(event.target.value) })} />
            </label>
            <label>
              Color start
              <input type="color" value={selectedLayer.colorStart} onChange={(event) => updateLayer(selectedLayer.id, { colorStart: event.target.value })} />
            </label>
            <label>
              Color end
              <input type="color" value={selectedLayer.colorEnd} onChange={(event) => updateLayer(selectedLayer.id, { colorEnd: event.target.value })} />
            </label>
            <label>
              Emissive start
              <input type="number" min="0" max="2" step="0.01" value={selectedLayer.emissiveStart} onChange={(event) => updateLayer(selectedLayer.id, { emissiveStart: Number(event.target.value) })} />
            </label>
            <label>
              Emissive end
              <input type="number" min="0" max="2" step="0.01" value={selectedLayer.emissiveEnd} onChange={(event) => updateLayer(selectedLayer.id, { emissiveEnd: Number(event.target.value) })} />
            </label>
            <label>
              Bloom strength
              <input type="number" min="0" max="1" step="0.01" value={selectedLayer.bloomStrength} onChange={(event) => updateLayer(selectedLayer.id, { bloomStrength: Number(event.target.value) })} />
            </label>
            <label>
              Noise strength
              <input type="number" min="0" max="1" step="0.01" value={selectedLayer.noiseStrength} onChange={(event) => updateLayer(selectedLayer.id, { noiseStrength: Number(event.target.value) })} />
            </label>
            <label>
              Noise scale
              <input type="number" min="1" step="0.1" value={selectedLayer.noiseScale} onChange={(event) => updateLayer(selectedLayer.id, { noiseScale: Number(event.target.value) })} />
            </label>
            <label>
              Texture speed
              <input type="number" step="0.01" value={selectedLayer.textureSpeed} onChange={(event) => updateLayer(selectedLayer.id, { textureSpeed: Number(event.target.value) })} />
            </label>
            <label>
              Blend mode
              <select value={selectedLayer.blendMode} onChange={(event) => updateLayer(selectedLayer.id, { blendMode: event.target.value as BeamLayer['blendMode'] })}>
                <option value="additive">Additive</option>
                <option value="screen">Screen</option>
                <option value="alpha">Alpha</option>
              </select>
            </label>
          </section>
        )}

        <section className="control-section export-section">
          <h2>Kotlin Draft</h2>
          <textarea readOnly value={exportText} aria-label="Kotlin beam draft export" />
        </section>
      </aside>
    </main>
  );
}

function pointLabel(index: number, mode: BeamMode): string {
  if (index === 0) {
    return 'Start';
  }
  if (mode === 'curved' && index === 1) {
    return 'Control';
  }
  return 'End';
}

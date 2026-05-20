import { useEffect, useState } from 'react';
import { BoxUtilPreviewPreset } from '../model/preset';
import { ParsePresetError, formatPresetJson, parsePresetJson } from '../model/parsePreset';
import { serializeGameExportPreset } from '../export/gameExport';
import { formatPresetKotlin } from '../export/kotlinExport';
import { DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY, PreviewOverlayLayerVisibility } from '../render/previewOverlayRenderer';
import { createDefaultPreset } from '../model/preset';

const BASIC_PRESET = {
  ...createDefaultPreset(),
  name: 'basic-trail',
  trailEntities: [
    {
      ...createDefaultPreset().trailEntities[0],
      id: 'astd_basic_trail',
      nodes: [
        { position: [-240, 0] as [number, number] },
        { position: [-120, 28] as [number, number] },
        { position: [0, 0] as [number, number] },
        { position: [130, -24] as [number, number] },
        { position: [260, 0] as [number, number] }
      ],
      startColor: [0.3, 1, 0.9, 1] as [number, number, number, number],
      endColor: [0.95, 0.25, 0.55, 0.06] as [number, number, number, number],
      startEmissive: [0.45, 1, 0.95, 1] as [number, number, number, number],
      endEmissive: [0.9, 0.35, 0.55, 0.2] as [number, number, number, number],
      startWidth: 42,
      endWidth: 8,
      texturePixels: 84,
      textureSpeed: 1.4,
      uvOffset: 0.15,
      fillStartAlpha: 0.9,
      fillEndAlpha: 0.12,
      fillStartFactor: 0.18,
      fillEndFactor: 0.3,
      jitterPower: 0.1,
      flick: true,
      syncFlick: false,
      stripLineMode: false,
      flowWhenPaused: true,
      flickWhenPaused: true,
      flickMixValue: 0.22,
      flickerSyncCode: 17,
      blendMode: 'additive' as const
    }
  ]
};

export interface ConfigPanelProps {
  preset: BoxUtilPreviewPreset;
  onPresetChange: (preset: BoxUtilPreviewPreset) => void;
  layerVisibility?: PreviewOverlayLayerVisibility;
  onLayerVisibilityChange?: (visibility: PreviewOverlayLayerVisibility) => void;
}

const layerToggleLabels: Array<{ key: keyof PreviewOverlayLayerVisibility; label: string }> = [
  { key: 'trail', label: 'Trail' },
  { key: 'head', label: 'Head' },
  { key: 'glow', label: 'Glow' },
  { key: 'mist', label: 'Mist' },
  { key: 'sideWisps', label: 'Side Wisps' },
  { key: 'ribbon', label: 'Ribbon' },
];

export function ConfigPanel({ preset, onPresetChange, layerVisibility = DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY, onLayerVisibilityChange }: ConfigPanelProps) {
  const [open, setOpen] = useState(() => getStorageItem('astd-projectile-vfx-config-open') !== 'false');
  const [jsonText, setJsonText] = useState(() => formatPresetJson(preset));
  const [exportText, setExportText] = useState(() => formatPresetJson(preset));
  const [errors, setErrors] = useState<ParsePresetError[]>([]);
  const [status, setStatus] = useState('');
  const [exportMode, setExportMode] = useState<'json' | 'kotlin'>('json');
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    setJsonText(formatPresetJson(preset));
    setExportText(exportMode === 'json' ? serializeGameExportPreset(preset) : formatPresetKotlin(preset));
    setCopied(false);
  }, [preset, exportMode]);

  useEffect(() => {
    setStorageItem('astd-projectile-vfx-config-open', String(open));
  }, [open]);

  const applyJson = () => {
    const result = parsePresetJson(jsonText);
    if (result.ok) {
      setErrors([]);
      setStatus('Preset applied.');
      onPresetChange(result.preset);
    } else {
      setErrors(result.errors);
      setStatus('');
    }
  };

  const exportJson = () => {
    setExportMode('json');
    setExportText(serializeGameExportPreset(preset));
    setErrors([]);
    setStatus('Game JSON preset exported.');
  };

  const exportKotlin = () => {
    setExportMode('kotlin');
    setExportText(formatPresetKotlin(preset));
    setErrors([]);
    setStatus('Kotlin preset exported.');
  };

  const handleCopy = () => {
    navigator.clipboard.writeText(exportText)
      .then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      })
      .catch(() => {
        setStatus('Failed to copy to clipboard.');
      });
  };

  const updateLayerVisibility = (key: keyof PreviewOverlayLayerVisibility, checked: boolean) => {
    onLayerVisibilityChange?.({ ...layerVisibility, [key]: checked });
  };

  return (
    <aside className="config-panel">
      <button type="button" className="panel-toggle" onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        Preset I/O {open ? '▾' : '▸'}
      </button>
      {open && (
        <div className="panel-body">
          <div className="panel-section built-in-presets">
            <div className="panel-inline-head">
              <strong>Preset Library</strong>
              <span className="muted-chip">Built-in Templates</span>
            </div>
            <div className="button-row compact row-layout">
              <button type="button" className="btn-secondary" onClick={() => { onPresetChange(createDefaultPreset()); setStatus('Loaded Default Preset.'); }}>
                Default Preset
              </button>
              <button type="button" className="btn-secondary" onClick={() => { onPresetChange(BASIC_PRESET); setStatus('Loaded Basic Trail Preset.'); }}>
                Basic Trail
              </button>
            </div>
          </div>
          <div className="panel-section" aria-label="Layer Toggles">
            <div className="panel-inline-head">
              <strong>Layer Toggles</strong>
              <span className="muted-chip">Preview Only</span>
            </div>
            <div className="button-row compact">
              {layerToggleLabels.map((toggle) => (
                <label key={toggle.key} className="layer-toggle">
                  <input
                    aria-label={`toggle-${toggle.key}`}
                    type="checkbox"
                    checked={layerVisibility[toggle.key]}
                    onChange={(event) => updateLayerVisibility(toggle.key, event.currentTarget.checked)}
                  />
                  {toggle.label}
                </label>
              ))}
            </div>
          </div>
          <div className="panel-section">
            <label htmlFor="preset-json">Import JSON</label>
            <textarea id="preset-json" value={jsonText} onChange={(event) => setJsonText(event.currentTarget.value)} />
            <div className="button-row compact">
              <button type="button" className="btn-primary" onClick={applyJson}>Apply JSON</button>
              <button type="button" className="btn-secondary" onClick={exportJson}>Export JSON</button>
              <button type="button" className="btn-secondary" onClick={exportKotlin}>Export Kotlin</button>
            </div>
          </div>
          <div className="panel-section">
            <div className="panel-inline-head border-b">
              <label htmlFor="preset-export">Game Export</label>
              <span className="muted-chip">{exportMode === 'json' ? 'JSON' : 'Kotlin'}</span>
            </div>
            <div className="export-action-row" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '4px 0' }}>
              <p className="panel-note" style={{ margin: 0 }}>Export includes Game Export settings only.</p>
              <button type="button" className="btn-action-small" onClick={handleCopy} style={{ padding: '2px 8px', fontSize: '11px' }}>
                {copied ? 'Copied' : 'Copy to Clipboard'}
              </button>
            </div>
            <textarea id="preset-export" readOnly value={exportText} />
          </div>
          {errors.length > 0 && (
            <ul className="error-list">
              {errors.map((error) => (
                <li key={`${error.path}:${error.message}`}>{error.path}: {error.message}</li>
              ))}
            </ul>
          )}
          {status && <p className="status-line">{status}</p>}
        </div>
      )}
    </aside>
  );
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

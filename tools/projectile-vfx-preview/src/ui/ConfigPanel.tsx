import { useEffect, useState } from 'react';
import { BoxUtilPreviewPreset } from '../model/preset';
import { ParsePresetError, formatPresetJson, parsePresetJson } from '../model/parsePreset';
import { formatPresetKotlin } from '../export/kotlinExport';
import { DEFAULT_PREVIEW_OVERLAY_LAYER_VISIBILITY, PreviewOverlayLayerVisibility } from '../render/previewOverlayRenderer';

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

  useEffect(() => {
    setJsonText(formatPresetJson(preset));
    setExportText(exportMode === 'json' ? formatPresetJson(preset) : formatPresetKotlin(preset));
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
    setExportText(formatPresetJson(preset));
    setErrors([]);
    setStatus('Preset exported.');
  };

  const exportKotlin = () => {
    setExportMode('kotlin');
    setExportText(formatPresetKotlin(preset));
    setErrors([]);
    setStatus('Kotlin preset exported.');
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
              <button type="button" onClick={applyJson}>Apply JSON</button>
              <button type="button" onClick={exportJson}>Export JSON</button>
              <button type="button" onClick={exportKotlin}>Export Kotlin</button>
            </div>
          </div>
          <div className="panel-section">
            <div className="panel-inline-head">
              <label htmlFor="preset-export">Export Preview</label>
              <span className="muted-chip">{exportMode === 'json' ? 'JSON' : 'Kotlin'}</span>
            </div>
            <p className="panel-note">Export includes Game Export settings only.</p>
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

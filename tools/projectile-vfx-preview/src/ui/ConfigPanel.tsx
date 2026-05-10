import { useEffect, useState } from 'react';
import { BoxUtilPreviewPreset } from '../model/preset';
import { ParsePresetError, formatPresetJson, parsePresetJson } from '../model/parsePreset';
import { formatPresetKotlin } from '../export/kotlinExport';

export interface ConfigPanelProps {
  preset: BoxUtilPreviewPreset;
  onPresetChange: (preset: BoxUtilPreviewPreset) => void;
}

export function ConfigPanel({ preset, onPresetChange }: ConfigPanelProps) {
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

  return (
    <aside className="config-panel">
      <button type="button" className="panel-toggle" onClick={() => setOpen((value) => !value)} aria-expanded={open}>
        Preset I/O {open ? '▾' : '▸'}
      </button>
      {open && (
        <div className="panel-body">
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

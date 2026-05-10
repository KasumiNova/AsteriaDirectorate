import { useState } from 'react';
import { BoxUtilPreviewPreset } from '../model/preset';

interface SavedVersion {
  name: string;
  preset: BoxUtilPreviewPreset;
}

export interface VersionCompareProps {
  preset: BoxUtilPreviewPreset;
  onPresetChange: (preset: BoxUtilPreviewPreset) => void;
}

export function VersionCompare({ preset, onPresetChange }: VersionCompareProps) {
  const [versionName, setVersionName] = useState('baseline');
  const [versions, setVersions] = useState<SavedVersion[]>([]);

  const saveVersion = () => {
    const snapshot: SavedVersion = {
      name: versionName.trim() || `version-${versions.length + 1}`,
      preset: structuredClone(preset),
    };
    setVersions((current) => [...current.filter((version) => version.name !== snapshot.name), snapshot]);
  };

  return (
    <section className="version-compare" aria-label="Version compare">
      <h2>Version Compare</h2>
      <label>
        Version name
        <input aria-label="Version name" value={versionName} onChange={(event) => setVersionName(event.currentTarget.value)} />
      </label>
      <button type="button" onClick={saveVersion}>Save Version</button>
      <ul>
        {versions.map((version) => (
          <li key={version.name}>
            <span>{version.name}</span>
            <button type="button" onClick={() => onPresetChange(structuredClone(version.preset))}>
              Restore {version.name}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

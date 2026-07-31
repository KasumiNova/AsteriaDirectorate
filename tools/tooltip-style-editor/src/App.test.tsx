import { describe, expect, test } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';

describe('app stylesheet', () => {
  test('uses a dark default Material editor shell with a wide editor rail', () => {
    const css = fs.readFileSync(path.resolve(__dirname, 'App.css'), 'utf8');

    expect(css).toContain('--md-sys-color-surface: #101415');
    expect(css).toContain('minmax(810px, 810px)');
    expect(css).toContain('color-scheme: dark');
  });
});

describe('app state and import behavior', () => {
  test('defines cached editor state and JSON import affordances', () => {
    const appSource = fs.readFileSync(path.resolve(__dirname, 'App.tsx'), 'utf8');

    expect(appSource).toContain('astd-tooltip-style-editor-state');
    expect(appSource).toContain('Import JSON');
    expect(appSource).toContain('JSON import applied.');
    expect(appSource).toContain('normalizeTooltipPreset(importedPreset)');
  });
});

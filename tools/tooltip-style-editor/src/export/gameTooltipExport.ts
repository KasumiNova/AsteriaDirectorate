import type { TooltipPreset } from '../model/tooltipPreset';

export type GameTooltipExport = {
  schemaVersion: 1;
  kind: TooltipPreset['kind'];
  hullmod: TooltipPreset['hullmod'];
  theme: TooltipPreset['theme'];
  background: TooltipPreset['background'];
  blocks: TooltipPreset['blocks'];
};

export const toGameTooltipExport = (preset: TooltipPreset): GameTooltipExport => ({
  schemaVersion: 1,
  kind: preset.kind,
  hullmod: preset.hullmod,
  theme: preset.theme,
  background: preset.background,
  blocks: preset.blocks,
});

export const serializeGameTooltipExport = (preset: TooltipPreset): string =>
  `${JSON.stringify(toGameTooltipExport(preset), null, 2)}\n`;

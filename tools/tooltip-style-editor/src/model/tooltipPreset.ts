import { createDefaultHullmodTooltipPreset } from './defaultHullmodPreset';

export const TOOLTIP_PRESET_STORAGE_VERSION = 'tooltip-style-editor/v3' as const;
export const TOOLTIP_PRESET_KIND = 'hullmod-tooltip' as const;

export type Rgba = {
  r: number;
  g: number;
  b: number;
  a: number;
};

export type TooltipBlockKind = 'paragraph' | 'section-heading' | 'spacer' | 'table';

export type TooltipColorRole = 'accent' | 'warning' | 'positive' | 'muted' | 'orange';

export type TooltipTextHighlight = {
  value: string;
  colorRole?: TooltipColorRole;
  color?: Rgba;
};

export type TooltipTableColumn = {
  id: string;
  label: string;
  width?: number;
  align?: 'start' | 'center' | 'end';
};

export type TooltipTableCell = {
  text: string;
  colorRole?: TooltipColorRole;
  color?: Rgba;
};

export type TooltipTableRow = {
  id: string;
  cells: TooltipTableCell[];
};

export type TooltipBlock = {
  id: string;
  kind: TooltipBlockKind;
  text: string;
  highlights?: TooltipTextHighlight[] | string[];
  backgroundColor?: Rgba;
  textColor?: Rgba;
  columns?: TooltipTableColumn[];
  rows?: TooltipTableRow[];
  padTop?: number;
  align?: 'start' | 'center' | 'end';
};

export type TooltipTheme = {
  panel: {
    width: number;
    minHeight: number;
    borderColor: Rgba;
    backgroundColor: Rgba;
  };
  text: {
      title: Rgba;
      designType: Rgba;
      body: Rgba;
      muted: Rgba;
      warning: Rgba;
      positive: Rgba;
      orange: Rgba;
    };
  section: {
    backgroundColor: Rgba;
    textColor: Rgba;
  };
};

export type TooltipPreset = {
  storageVersion: typeof TOOLTIP_PRESET_STORAGE_VERSION;
  kind: typeof TOOLTIP_PRESET_KIND;
  hullmod: {
    id: string;
    displayName: string;
    designType: string;
    designTypeColor?: Rgba;
    tierLabel: string;
    iconLabel: string;
    opCost: number;
  };
  theme: TooltipTheme;
  background: {
    shaderId: string;
    fragmentShader: string;
    uniforms: Record<string, number | Rgba>;
  };
  blocks: TooltipBlock[];
};

type PlainObject = Record<string, unknown>;

export type TooltipPresetPatch = {
  [Key in keyof TooltipPreset]?: TooltipPreset[Key] extends Array<infer Item>
    ? Item[]
    : TooltipPreset[Key] extends object
      ? {
          [ChildKey in keyof TooltipPreset[Key]]?: TooltipPreset[Key][ChildKey] extends object
            ? Partial<TooltipPreset[Key][ChildKey]>
            : TooltipPreset[Key][ChildKey];
        }
      : TooltipPreset[Key];
};

const isPlainObject = (value: unknown): value is PlainObject =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const deepMerge = <T>(base: T, override: unknown): T => {
  if (override === undefined) {
    return base;
  }

  if (Array.isArray(base)) {
    return (Array.isArray(override) ? override : base) as T;
  }

  if (!isPlainObject(base) || !isPlainObject(override)) {
    return override as T;
  }

  const merged: PlainObject = { ...base };
  for (const [key, value] of Object.entries(override)) {
    merged[key] = deepMerge((base as PlainObject)[key], value);
  }

  return merged as T;
};

export const normalizeTooltipPreset = (partial: TooltipPresetPatch = {}): TooltipPreset => {
  const merged = deepMerge(createDefaultHullmodTooltipPreset(), partial);

  return {
    ...merged,
    storageVersion: TOOLTIP_PRESET_STORAGE_VERSION,
    kind: TOOLTIP_PRESET_KIND,
  };
};

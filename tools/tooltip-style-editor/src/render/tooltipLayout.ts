import type { TooltipBlock, TooltipBlockKind, TooltipPreset } from '../model/tooltipPreset';

export type TooltipBlockLayout = {
  id: string;
  top: number;
  height: number;
  lineCount: number;
};

export type TooltipLayout = {
  width: number;
  height: number;
  blocks: TooltipBlockLayout[];
};

const BASE_HORIZONTAL_PADDING = 18;
const HEADER_HEIGHT = 58;
const FOOTER_MARGIN = 16;
const PARAGRAPH_LINE_HEIGHT = 24;
const HEADING_LINE_HEIGHT = 24;
const TABLE_ROW_HEIGHT = 28;

const lineHeightForKind = (kind: TooltipBlockKind): number => {
  if (kind === 'section-heading') {
    return HEADING_LINE_HEIGHT;
  }

  return PARAGRAPH_LINE_HEIGHT;
};

const estimateCharacterWidth = (character: string): number => {
  if (/[\u0000-\u007f]/.test(character)) {
    return 8;
  }

  return 16;
};

const estimateTextWidth = (text: string): number =>
  Array.from(text).reduce((total, character) => total + estimateCharacterWidth(character), 0);

const estimateLineCount = (block: TooltipBlock, contentWidth: number): number => {
  if (block.kind === 'spacer') {
    return 0;
  }

  if (block.kind === 'table') {
    return (block.rows?.length ?? 0) + 1;
  }

  if (block.text.length === 0) {
    return 1;
  }

  const textWidth = estimateTextWidth(block.text);
  return Math.max(1, Math.ceil(textWidth / contentWidth));
};

export const estimateTooltipLayout = (preset: TooltipPreset): TooltipLayout => {
  const width = preset.theme.panel.width;
  const contentWidth = Math.max(1, width - BASE_HORIZONTAL_PADDING * 2);
  let cursor = HEADER_HEIGHT;

  const blocks = preset.blocks.map((block) => {
    cursor += block.padTop ?? 0;

    const lineCount = estimateLineCount(block, contentWidth);
    const height = block.kind === 'spacer'
      ? 8
      : block.kind === 'table'
        ? lineCount * TABLE_ROW_HEIGHT
        : lineCount * lineHeightForKind(block.kind);
    const top = cursor;

    cursor += height;

    return {
      id: block.id,
      top,
      height,
      lineCount,
    };
  });

  const height = Math.max(preset.theme.panel.minHeight, cursor + FOOTER_MARGIN);

  return {
    width,
    height,
    blocks,
  };
};

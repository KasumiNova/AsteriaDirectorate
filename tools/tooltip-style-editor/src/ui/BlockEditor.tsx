import { useState } from 'react';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/Delete';
import DragIndicatorIcon from '@mui/icons-material/DragIndicator';
import {
  Box,
  Button,
  IconButton,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import type {
  Rgba,
  TooltipBlock,
  TooltipBlockKind,
  TooltipPreset,
  TooltipTableCell,
} from '../model/tooltipPreset';
import { COLOR_SWATCHES, ColorPicker } from './ColorPicker';

type BlockEditorProps = {
  preset: TooltipPreset;
  onPresetChange: (preset: TooltipPreset) => void;
};

const DESIGN_TYPE_COLOR: Rgba = { r: 118, g: 139, b: 139, a: 1 };

const createBlock = (kind: TooltipBlockKind): TooltipBlock => {
  const id = `${kind}-${Date.now().toString(36)}`;
  if (kind === 'section-heading') {
    return { id, kind, text: '新段落标题', padTop: 12, align: 'center' };
  }
  if (kind === 'table') {
    return {
      id,
      kind,
      text: '',
      padTop: 8,
      columns: [
        { id: 'col-a', label: '项目', align: 'center' },
        { id: 'col-b', label: '数值', align: 'center' },
        { id: 'col-c', label: '备注', align: 'center' },
      ],
      rows: [
        {
          id: 'row-a',
          cells: [
            { text: '护卫舰', colorRole: 'warning' },
            { text: '2+', colorRole: 'warning' },
            { text: '4', colorRole: 'warning' },
          ],
        },
      ],
    };
  }
  if (kind === 'spacer') {
    return { id, kind, text: '', padTop: 8 };
  }
  return { id, kind: 'paragraph', text: '新的说明文本。', padTop: 8, align: 'start' };
};

export const BlockEditor = ({ preset, onPresetChange }: BlockEditorProps) => {
  const [draggedBlockId, setDraggedBlockId] = useState<string | null>(null);
  const [dropTargetBlockId, setDropTargetBlockId] = useState<string | null>(null);
  const [draggedColumn, setDraggedColumn] = useState<{ blockId: string; columnIndex: number } | null>(null);
  const [draggedRow, setDraggedRow] = useState<{ blockId: string; rowId: string } | null>(null);

  const updateHullmod = (field: 'displayName' | 'designType', value: string) => {
    onPresetChange({
      ...preset,
      hullmod: {
        ...preset.hullmod,
        [field]: value,
        tierLabel: field === 'designType' ? `设计类型： ${value}` : preset.hullmod.tierLabel,
      },
    });
  };
  const updateDesignTypeColor = (designTypeColor: Rgba) => {
    onPresetChange({
      ...preset,
      hullmod: {
        ...preset.hullmod,
        designTypeColor,
      },
    });
  };

  const updateBlocks = (blocks: TooltipBlock[]) => onPresetChange({ ...preset, blocks });
  const updateBlock = (blockId: string, patch: Partial<TooltipBlock>) => {
    updateBlocks(preset.blocks.map((block) => (block.id === blockId ? { ...block, ...patch } : block)));
  };

  const addBlock = (kind: TooltipBlockKind) => updateBlocks([...preset.blocks, createBlock(kind)]);
  const removeBlock = (blockId: string) => updateBlocks(preset.blocks.filter((block) => block.id !== blockId));
  const moveBlock = (sourceId: string, targetId: string) => {
    if (sourceId === targetId) {
      return;
    }
    const sourceIndex = preset.blocks.findIndex((block) => block.id === sourceId);
    const targetIndex = preset.blocks.findIndex((block) => block.id === targetId);
    if (sourceIndex < 0 || targetIndex < 0) {
      return;
    }
    const nextBlocks = [...preset.blocks];
    const [sourceBlock] = nextBlocks.splice(sourceIndex, 1);
    nextBlocks.splice(targetIndex, 0, sourceBlock);
    updateBlocks(nextBlocks);
  };
  const previewMoveBlock = (targetId: string) => {
    if (!draggedBlockId || draggedBlockId === targetId) {
      return;
    }
    moveBlock(draggedBlockId, targetId);
    setDropTargetBlockId(targetId);
  };

  const addTableRow = (block: TooltipBlock) => {
    const columns = block.columns ?? [];
    updateBlock(block.id, {
      rows: [
        ...(block.rows ?? []),
        {
          id: `row-${Date.now().toString(36)}`,
          cells: columns.map(() => ({ text: '', colorRole: 'warning' })),
        },
      ],
    });
  };

  const addTableColumn = (block: TooltipBlock) => {
    const columns = block.columns ?? [];
    const columnIndex = columns.length + 1;
    updateBlock(block.id, {
      columns: [
        ...columns,
        {
          id: `col-${Date.now().toString(36)}`,
          label: `列 ${columnIndex}`,
          align: 'center',
        },
      ],
      rows: (block.rows ?? []).map((row) => ({
        ...row,
        cells: [...row.cells, { text: '', colorRole: 'warning' }],
      })),
    });
  };

  const removeTableColumn = (block: TooltipBlock, columnIndex: number) => {
    updateBlock(block.id, {
      columns: (block.columns ?? []).filter((_, index) => index !== columnIndex),
      rows: (block.rows ?? []).map((row) => ({
        ...row,
        cells: row.cells.filter((_, index) => index !== columnIndex),
      })),
    });
  };

  const removeTableRow = (block: TooltipBlock, rowId: string) => {
    updateBlock(block.id, {
      rows: (block.rows ?? []).filter((row) => row.id !== rowId),
    });
  };

  const moveTableColumn = (block: TooltipBlock, sourceIndex: number, targetIndex: number) => {
    if (sourceIndex === targetIndex) {
      return;
    }
    const columns = [...(block.columns ?? [])];
    const [sourceColumn] = columns.splice(sourceIndex, 1);
    columns.splice(targetIndex, 0, sourceColumn);
    updateBlock(block.id, {
      columns,
      rows: (block.rows ?? []).map((row) => {
        const cells = [...row.cells];
        const [sourceCell] = cells.splice(sourceIndex, 1);
        cells.splice(targetIndex, 0, sourceCell ?? { text: '' });
        return { ...row, cells };
      }),
    });
  };

  const moveTableRow = (block: TooltipBlock, sourceRowId: string, targetRowId: string) => {
    if (sourceRowId === targetRowId) {
      return;
    }
    const rows = [...(block.rows ?? [])];
    const sourceIndex = rows.findIndex((row) => row.id === sourceRowId);
    const targetIndex = rows.findIndex((row) => row.id === targetRowId);
    if (sourceIndex < 0 || targetIndex < 0) {
      return;
    }
    const [sourceRow] = rows.splice(sourceIndex, 1);
    rows.splice(targetIndex, 0, sourceRow);
    updateBlock(block.id, { rows });
  };

  const updateTableCell = (block: TooltipBlock, rowId: string, cellIndex: number, patch: Partial<TooltipTableCell>) => {
    updateBlock(block.id, {
      rows: (block.rows ?? []).map((row) =>
        row.id === rowId
          ? {
              ...row,
              cells: row.cells.map((cell, index) => (index === cellIndex ? { ...cell, ...patch } : cell)),
            }
          : row,
      ),
    });
  };

  return (
    <Paper component="section" className="editor-panel" elevation={1} aria-labelledby="content-editor-heading">
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography id="content-editor-heading" variant="h6">
          Content
        </Typography>
        <Stack direction="row" spacing={1}>
          <Button size="small" startIcon={<AddIcon />} variant="outlined" onClick={() => addBlock('paragraph')}>
            Text
          </Button>
          <Button size="small" startIcon={<AddIcon />} variant="outlined" onClick={() => addBlock('section-heading')}>
            Section
          </Button>
          <Button size="small" startIcon={<AddIcon />} variant="outlined" onClick={() => addBlock('table')}>
            Table
          </Button>
        </Stack>
      </Stack>

      <Stack spacing={1.5} sx={{ mt: 2 }}>
        <Stack direction="row" spacing={1.5}>
          <TextField
            fullWidth
            id="tooltip-hullmod-name"
            label="Hullmod name"
            name="tooltip-hullmod-name"
            size="small"
            value={preset.hullmod.displayName}
            onChange={(event) => updateHullmod('displayName', event.target.value)}
          />
          <TextField
            fullWidth
            id="tooltip-design-type"
            label="Design type"
            name="tooltip-design-type"
            size="small"
            value={preset.hullmod.designType}
            onChange={(event) => updateHullmod('designType', event.target.value)}
          />
          <Box sx={{ minWidth: 148 }}>
            <SwatchRow
              compact
              label="Design value color"
              selected={preset.hullmod.designTypeColor ?? DESIGN_TYPE_COLOR}
              onChange={updateDesignTypeColor}
            />
          </Box>
        </Stack>

        {preset.blocks.map((block, blockIndex) => (
          <Paper
            className={[
              'content-block-card',
              draggedBlockId === block.id ? 'content-block-card--dragging' : '',
              dropTargetBlockId === block.id ? 'content-block-card--drop-target' : '',
            ].filter(Boolean).join(' ')}
            data-testid={`tooltip-block-card-${block.id}`}
            elevation={0}
            key={block.id}
            onDragOver={(event) => event.preventDefault()}
            onDragEnter={(event) => {
              event.preventDefault();
              if (draggedBlockId && draggedBlockId !== block.id) {
                previewMoveBlock(block.id);
              }
            }}
            onDragLeave={() => {
              if (dropTargetBlockId === block.id) {
                setDropTargetBlockId(null);
              }
            }}
            onDrop={(event) => {
              event.preventDefault();
              const sourceId = event.dataTransfer?.getData('text/plain') || draggedBlockId;
              if (sourceId && sourceId !== block.id) {
                moveBlock(sourceId, block.id);
              }
              setDraggedBlockId(null);
              setDropTargetBlockId(null);
            }}
          >
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
              <Stack
                aria-label={`Drag ${block.kind}`}
                className="content-block-card__title"
                direction="row"
                draggable
                role="button"
                spacing={0.75}
                sx={{ alignItems: 'center' }}
                tabIndex={0}
                onDragEnd={() => {
                  setDraggedBlockId(null);
                  setDropTargetBlockId(null);
                }}
                onDragStart={(event) => {
                  setDraggedBlockId(block.id);
                  if (event.dataTransfer) {
                    event.dataTransfer.effectAllowed = 'move';
                    event.dataTransfer.setData('text/plain', block.id);
                  }
                }}
              >
                <DragIndicatorIcon fontSize="small" />
                <Typography variant="caption">
                  {blockIndex + 1}. {block.kind}
                </Typography>
              </Stack>
              <IconButton aria-label={`Delete ${block.kind}`} size="small" onClick={() => removeBlock(block.id)}>
                <DeleteIcon fontSize="small" />
              </IconButton>
            </Stack>

            {block.kind === 'paragraph' || block.kind === 'section-heading' ? (
              <Stack spacing={1} sx={{ mt: 1 }}>
                <TextField
                  fullWidth
                  id={`tooltip-block-${block.id}`}
                  label={block.kind === 'section-heading' ? 'Section title' : 'Text'}
                  multiline={block.kind === 'paragraph'}
                  name={`tooltip-block-${block.id}`}
                  minRows={block.kind === 'paragraph' ? 2 : undefined}
                  size="small"
                  value={block.text}
                  onChange={(event) => updateBlock(block.id, { text: event.target.value })}
                />
                {block.kind === 'paragraph' ? (
                  <HighlightEditor block={block} onChange={(highlights) => updateBlock(block.id, { highlights })} />
                ) : null}
                {block.kind === 'section-heading' ? (
                  <Stack className="section-heading-colors" direction="row" spacing={1}>
                    <SwatchRow
                      compact
                      label="Heading bg"
                      selected={block.backgroundColor ?? preset.theme.section.backgroundColor}
                      showAlpha
                      onChange={(backgroundColor) => updateBlock(block.id, { backgroundColor })}
                    />
                    <SwatchRow
                      compact
                      label="Heading text"
                      selected={block.textColor ?? preset.theme.section.textColor}
                      showAlpha
                      onChange={(textColor) => updateBlock(block.id, { textColor })}
                    />
                  </Stack>
                ) : null}
              </Stack>
            ) : null}

            {block.kind === 'table' ? (
              <Stack spacing={1} sx={{ mt: 1 }}>
                <Stack direction="row" spacing={1}>
                  {(block.columns ?? []).map((column, columnIndex) => (
                    <Stack
                      aria-label={`Drop before column ${columnIndex + 1}`}
                      direction="row"
                      spacing={0.5}
                      sx={{ alignItems: 'flex-start', flex: 1 }}
                      key={column.id}
                      onDragOver={(event) => event.preventDefault()}
                      onDrop={(event) => {
                        event.preventDefault();
                        if (draggedColumn?.blockId === block.id) {
                          moveTableColumn(block, draggedColumn.columnIndex, columnIndex);
                        }
                        setDraggedColumn(null);
                      }}
                    >
                      <IconButton
                        aria-label={`Drag column ${columnIndex + 1}`}
                        className="table-drag-handle"
                        draggable
                        size="small"
                        onDragEnd={() => setDraggedColumn(null)}
                        onDragStart={(event) => {
                          setDraggedColumn({ blockId: block.id, columnIndex });
                          if (event.dataTransfer) {
                            event.dataTransfer.effectAllowed = 'move';
                            event.dataTransfer.setData('text/plain', `${block.id}:column:${columnIndex}`);
                          }
                        }}
                      >
                        <DragIndicatorIcon fontSize="small" />
                      </IconButton>
                      <TextField
                        fullWidth
                        label={`Column ${columnIndex + 1}`}
                        size="small"
                        value={column.label}
                        onChange={(event) =>
                          updateBlock(block.id, {
                            columns: (block.columns ?? []).map((item) =>
                              item.id === column.id ? { ...item, label: event.target.value } : item,
                            ),
                          })
                        }
                      />
                      <IconButton
                        aria-label={`Delete column ${columnIndex + 1}`}
                        className="table-delete-button"
                        size="small"
                        onClick={() => removeTableColumn(block, columnIndex)}
                      >
                        <DeleteIcon fontSize="small" />
                      </IconButton>
                    </Stack>
                  ))}
                </Stack>
                {(block.rows ?? []).map((row, rowIndex) => (
                  <Stack
                    aria-label={`Drop before row ${rowIndex + 1}`}
                    direction="row"
                    spacing={1}
                    sx={{ alignItems: 'flex-start' }}
                    key={row.id}
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={(event) => {
                      event.preventDefault();
                      if (draggedRow?.blockId === block.id) {
                        moveTableRow(block, draggedRow.rowId, row.id);
                      }
                      setDraggedRow(null);
                    }}
                  >
                    <IconButton
                      aria-label={`Drag row ${rowIndex + 1}`}
                      className="table-drag-handle"
                      draggable
                      size="small"
                      onDragEnd={() => setDraggedRow(null)}
                      onDragStart={(event) => {
                        setDraggedRow({ blockId: block.id, rowId: row.id });
                        if (event.dataTransfer) {
                          event.dataTransfer.effectAllowed = 'move';
                          event.dataTransfer.setData('text/plain', `${block.id}:row:${row.id}`);
                        }
                      }}
                    >
                      <DragIndicatorIcon fontSize="small" />
                    </IconButton>
                    {row.cells.map((cell, cellIndex) => (
                      <Stack spacing={0.75} sx={{ flex: 1 }} key={`${row.id}-${cellIndex}`}>
                        <TextField
                          fullWidth
                          label={`R${rowIndex + 1} C${cellIndex + 1}`}
                          size="small"
                          value={cell.text}
                          onChange={(event) => updateTableCell(block, row.id, cellIndex, { text: event.target.value })}
                        />
                        <SwatchRow
                          compact
                          label="Cell color"
                          selected={cell.color}
                          onChange={(color) => updateTableCell(block, row.id, cellIndex, { color, colorRole: undefined })}
                        />
                      </Stack>
                    ))}
                    <IconButton
                      aria-label={`Delete row ${rowIndex + 1}`}
                      className="table-delete-button table-delete-button--row"
                      size="small"
                      onClick={() => removeTableRow(block, row.id)}
                    >
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </Stack>
                ))}
                <Stack direction="row" spacing={1}>
                  <Button size="small" startIcon={<AddIcon />} variant="outlined" onClick={() => addTableRow(block)}>
                    Add row
                  </Button>
                  <Button size="small" startIcon={<AddIcon />} variant="outlined" onClick={() => addTableColumn(block)}>
                    Add column
                  </Button>
                </Stack>
              </Stack>
            ) : null}
          </Paper>
        ))}
      </Stack>
    </Paper>
  );
};

function HighlightEditor({
  block,
  onChange,
}: {
  block: TooltipBlock;
  onChange: (highlights: NonNullable<TooltipBlock['highlights']>) => void;
}) {
  const highlights = (block.highlights ?? []).map((highlight) =>
    typeof highlight === 'string' ? { value: highlight } : highlight,
  );

  const updateHighlight = (index: number, patch: Partial<(typeof highlights)[number]>) => {
    onChange(highlights.map((highlight, itemIndex) => (itemIndex === index ? { ...highlight, ...patch } : highlight)));
  };

  const removeHighlight = (index: number) => {
    onChange(highlights.filter((_, itemIndex) => itemIndex !== index));
  };

  return (
    <Stack spacing={1}>
      {highlights.map((highlight, index) => (
        <Stack className="highlight-row" direction="row" spacing={1} key={`${block.id}-hl-${index}`}>
          <TextField
            fullWidth
            label="Colored text"
            size="small"
            value={highlight.value}
            onChange={(event) => updateHighlight(index, { value: event.target.value })}
          />
          <SwatchRow
            compact
            label="Text color"
            selected={highlight.color}
            onChange={(color) => updateHighlight(index, { color, colorRole: undefined })}
          />
          <IconButton aria-label="Delete colored span" size="small" onClick={() => removeHighlight(index)}>
            <DeleteIcon fontSize="small" />
          </IconButton>
        </Stack>
      ))}
      <Button
        size="small"
        startIcon={<AddIcon />}
        variant="text"
        onClick={() => onChange([...highlights, { value: '', color: COLOR_SWATCHES[1].color }])}
      >
        Add colored span
      </Button>
    </Stack>
  );
}

function SwatchRow({
  label,
  selected,
  compact = false,
  showAlpha = false,
  onChange,
}: {
  label: string;
  selected?: Rgba;
  compact?: boolean;
  showAlpha?: boolean;
  onChange: (color: Rgba) => void;
}) {
  return (
    <Box className={compact ? 'swatch-row swatch-row--compact' : 'swatch-row'}>
      <Typography variant="caption">{label}</Typography>
      <ColorPicker compact={compact} label={label} showAlpha={showAlpha} value={selected} onChange={onChange} />
    </Box>
  );
}

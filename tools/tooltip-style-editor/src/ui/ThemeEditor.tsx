import { Paper, Stack, TextField, Typography } from '@mui/material';
import type { Rgba, TooltipPreset } from '../model/tooltipPreset';
import { ColorPicker } from './ColorPicker';

type ThemeEditorProps = {
  preset: TooltipPreset;
  onPresetChange: (preset: TooltipPreset) => void;
};

type ColorPath = 'borderColor' | 'title' | 'warning' | 'sectionBackground';

export const ThemeEditor = ({ preset, onPresetChange }: ThemeEditorProps) => {
  const updatePanelWidth = (width: number) => {
    onPresetChange({
      ...preset,
      theme: {
        ...preset.theme,
        panel: {
          ...preset.theme.panel,
          width,
        },
      },
    });
  };

  const updateColor = (path: ColorPath, color: Rgba) => {
    const next = { ...preset, theme: { ...preset.theme } };

    if (path === 'borderColor') {
      next.theme.panel = {
        ...preset.theme.panel,
        borderColor: color,
      };
    }

    if (path === 'title') {
      next.theme.text = {
        ...preset.theme.text,
        title: color,
      };
    }

    if (path === 'warning') {
      next.theme.text = {
        ...preset.theme.text,
        warning: color,
      };
    }

    if (path === 'sectionBackground') {
      next.theme.section = {
        ...preset.theme.section,
        backgroundColor: color,
      };
    }

    onPresetChange(next);
  };

  return (
    <Paper component="section" className="editor-panel" elevation={1} aria-labelledby="theme-editor-heading">
      <Typography id="theme-editor-heading" variant="h6">
        Theme
      </Typography>

      <Stack spacing={2} sx={{ mt: 2 }}>
        <TextField
          fullWidth
          id="tooltip-panel-width"
          label="Panel width"
          name="tooltip-panel-width"
          size="small"
          type="number"
          value={preset.theme.panel.width}
          onChange={(event) => updatePanelWidth(Number(event.target.value))}
          slotProps={{ htmlInput: { min: 420, max: 760, step: 4 } }}
        />

        <ColorField
          label="Border color"
          value={preset.theme.panel.borderColor}
          onChange={(value) => updateColor('borderColor', value)}
        />
        <ColorField
          label="Title color"
          value={preset.theme.text.title}
          onChange={(value) => updateColor('title', value)}
        />
        <ColorField
          label="Warning color"
          value={preset.theme.text.warning}
          onChange={(value) => updateColor('warning', value)}
        />
        <ColorField
          label="Section background"
          value={preset.theme.section.backgroundColor}
          onChange={(value) => updateColor('sectionBackground', value)}
        />
      </Stack>
    </Paper>
  );
};

function ColorField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: Rgba;
  onChange: (value: Rgba) => void;
}) {
  return (
    <div className="color-field">
      <Typography variant="body2">{label}</Typography>
      <ColorPicker label={label} value={value} onChange={onChange} />
    </div>
  );
}

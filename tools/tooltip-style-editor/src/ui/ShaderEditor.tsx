import RestartAltIcon from '@mui/icons-material/RestartAlt';
import { Alert, Box, Button, MenuItem, Paper, Slider, Stack, TextField, Typography } from '@mui/material';
import {
  TOOLTIP_BACKGROUND_SHADER_PRESETS,
  createDefaultHullmodTooltipPreset,
} from '../model/defaultHullmodPreset';
import { validateFragmentShaderSource } from '../render/webgl/shaderCompiler';
import type { Rgba, TooltipPreset } from '../model/tooltipPreset';
import { ColorPicker } from './ColorPicker';

type ShaderEditorProps = {
  preset: TooltipPreset;
  onPresetChange: (preset: TooltipPreset) => void;
};

const DEFAULT_PRIMARY_COLOR: Rgba = { r: 18, g: 72, b: 94, a: 1 };
const DEFAULT_ACCENT_COLOR: Rgba = { r: 92, g: 230, b: 255, a: 1 };
const DEFAULT_INTENSITY = 1;

const isRgba = (value: number | Rgba | undefined): value is Rgba =>
  typeof value === 'object' && value !== null && 'r' in value;

const uniformColor = (
  uniforms: TooltipPreset['background']['uniforms'],
  key: 'u_primaryColor' | 'u_accentColor',
  fallback: Rgba,
): Rgba => {
  const value = uniforms[key];
  return isRgba(value) ? value : fallback;
};

const uniformNumber = (
  uniforms: TooltipPreset['background']['uniforms'],
  key: 'u_intensity',
  fallback: number,
): number => {
  const value = uniforms[key];
  return typeof value === 'number' ? value : fallback;
};

export const ShaderEditor = ({ preset, onPresetChange }: ShaderEditorProps) => {
  const validation = validateFragmentShaderSource(preset.background.fragmentShader);
  const primaryColor = uniformColor(
    preset.background.uniforms,
    'u_primaryColor',
    DEFAULT_PRIMARY_COLOR,
  );
  const accentColor = uniformColor(preset.background.uniforms, 'u_accentColor', DEFAULT_ACCENT_COLOR);
  const intensity = uniformNumber(preset.background.uniforms, 'u_intensity', DEFAULT_INTENSITY);

  const updateBackground = (background: TooltipPreset['background']) => {
    onPresetChange({
      ...preset,
      background,
    });
  };

  const updateFragmentShader = (fragmentShader: string) => {
    updateBackground({
      ...preset.background,
      fragmentShader,
    });
  };

  const updateUniform = (key: string, value: number | Rgba) => {
    updateBackground({
      ...preset.background,
      uniforms: {
        ...preset.background.uniforms,
        [key]: value,
      },
    });
  };

  const resetShader = () => {
    const defaultBackground = createDefaultHullmodTooltipPreset().background;
    updateBackground({
      ...preset.background,
      shaderId: defaultBackground.shaderId,
      fragmentShader: defaultBackground.fragmentShader,
      uniforms: defaultBackground.uniforms,
    });
  };

  const selectPreset = (shaderId: string) => {
    const shader = TOOLTIP_BACKGROUND_SHADER_PRESETS.find((item) => item.id === shaderId);
    if (!shader) {
      return;
    }
    updateBackground({
      ...preset.background,
      shaderId: shader.id,
      fragmentShader: shader.fragmentShader,
    });
  };

  return (
    <Paper component="section" className="editor-panel" elevation={1} aria-labelledby="shader-editor-heading">
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography id="shader-editor-heading" variant="h6">
          Shader
        </Typography>
        <Button size="small" startIcon={<RestartAltIcon />} variant="outlined" onClick={resetShader}>
          Reset
        </Button>
      </Stack>

      <Stack spacing={2} sx={{ mt: 2 }}>
        <TextField
          fullWidth
          label="Shader preset"
          select
          size="small"
          value={preset.background.shaderId}
          onChange={(event) => selectPreset(event.target.value)}
        >
          {TOOLTIP_BACKGROUND_SHADER_PRESETS.map((shader) => (
            <MenuItem key={shader.id} value={shader.id}>
              {shader.name}
            </MenuItem>
          ))}
        </TextField>

        <TextField
          fullWidth
          id="tooltip-fragment-shader"
          label="Fragment shader"
          multiline
          name="tooltip-fragment-shader"
          minRows={14}
          value={preset.background.fragmentShader}
          onChange={(event) => updateFragmentShader(event.target.value)}
          className="shader-editor__source"
          slotProps={{ htmlInput: { spellCheck: false } }}
        />

        {!validation.ok ? <Alert severity="error">{validation.message}</Alert> : null}

        <Box>
          <Typography component="label" htmlFor="tooltip-shader-intensity" variant="body2">
            Intensity
          </Typography>
          <Slider
            id="tooltip-shader-intensity"
            max={2}
            min={0}
            name="tooltip-shader-intensity"
            step={0.05}
            value={intensity}
            onChange={(_, value) => updateUniform('u_intensity', Array.isArray(value) ? value[0] : value)}
          />
        </Box>

        <ColorField
          label="Primary color"
          value={primaryColor}
          onChange={(value) => updateUniform('u_primaryColor', value)}
        />
        <ColorField
          label="Accent color"
          value={accentColor}
          onChange={(value) => updateUniform('u_accentColor', value)}
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
    <Box className="color-field">
      <Typography variant="body2">{label}</Typography>
      <ColorPicker label={label} value={value} onChange={onChange} />
    </Box>
  );
}

import { useEffect, useState } from 'react';
import { Box, ButtonBase, Popover, Slider, Stack, TextField, Typography } from '@mui/material';
import type { Rgba } from '../model/tooltipPreset';

export const COLOR_SWATCHES: Array<{ label: string; color: Rgba }> = [
  { label: 'Body', color: { r: 232, g: 244, b: 244, a: 1 } },
  { label: 'Yellow', color: { r: 255, g: 224, b: 36, a: 1 } },
  { label: 'Green', color: { r: 96, g: 224, b: 126, a: 1 } },
  { label: 'Blue', color: { r: 106, g: 169, b: 255, a: 1 } },
  { label: 'Orange', color: { r: 255, g: 148, b: 42, a: 1 } },
  { label: 'Muted', color: { r: 118, g: 139, b: 139, a: 1 } },
];

export const rgbaToHex = (color: Rgba): string =>
  `#${[color.r, color.g, color.b]
    .map((channel) => Math.max(0, Math.min(255, Math.round(channel))).toString(16).padStart(2, '0'))
    .join('')}`;

export const hexToRgba = (hex: string, alpha = 1): Rgba => {
  const value = hex.replace('#', '');
  return {
    r: Number.parseInt(value.slice(0, 2), 16),
    g: Number.parseInt(value.slice(2, 4), 16),
    b: Number.parseInt(value.slice(4, 6), 16),
    a: alpha,
  };
};

const isHexColor = (value: string) => /^#[0-9a-fA-F]{6}$/.test(value);

const channelLabel = {
  r: 'Red',
  g: 'Green',
  b: 'Blue',
} as const;

type ColorPickerProps = {
  label: string;
  value?: Rgba;
  fallback?: Rgba;
  compact?: boolean;
  showAlpha?: boolean;
  onChange: (color: Rgba) => void;
};

export const ColorPicker = ({
  label,
  value,
  fallback = COLOR_SWATCHES[0].color,
  compact = false,
  showAlpha = false,
  onChange,
}: ColorPickerProps) => {
  const color = value ?? fallback;
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [hexDraft, setHexDraft] = useState(rgbaToHex(color));
  const open = Boolean(anchorEl);

  useEffect(() => {
    setHexDraft(rgbaToHex(color));
  }, [color.r, color.g, color.b]);

  const updateChannel = (channel: 'r' | 'g' | 'b', nextValue: number | number[]) => {
    onChange({
      ...color,
      [channel]: Array.isArray(nextValue) ? nextValue[0] : nextValue,
    });
  };

  const updateHex = (nextHex: string) => {
    setHexDraft(nextHex);
    if (isHexColor(nextHex)) {
      onChange(hexToRgba(nextHex, color.a));
    }
  };

  const updateAlpha = (nextValue: number | number[]) => {
    onChange({
      ...color,
      a: Array.isArray(nextValue) ? nextValue[0] : nextValue,
    });
  };

  return (
    <>
      <ButtonBase
        aria-label={label}
        className={compact ? 'color-picker-trigger color-picker-trigger--compact' : 'color-picker-trigger'}
        onClick={(event) => setAnchorEl(event.currentTarget)}
      >
        <span className="color-picker-trigger__swatch" style={{ backgroundColor: rgbaToHex(color) }} />
        {!compact ? <span className="color-picker-trigger__value">{rgbaToHex(color)}</span> : null}
      </ButtonBase>

      <Popover
        anchorEl={anchorEl}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        marginThreshold={16}
        open={open}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        onClose={() => setAnchorEl(null)}
        slotProps={{
          paper: {
            className: 'color-picker-popover',
          },
        }}
      >
        <Stack spacing={1.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
            <Typography variant="subtitle2">{label}</Typography>
            <Box className="color-picker-preview" style={{ backgroundColor: rgbaToHex(color) }} />
          </Stack>

          <Box className="color-picker-swatches">
            {COLOR_SWATCHES.map((swatch) => (
              <button
                aria-label={`${label} preset ${swatch.label}`}
                className={rgbaToHex(color) === rgbaToHex(swatch.color) ? 'color-swatch active' : 'color-swatch'}
                key={swatch.label}
                style={{ backgroundColor: rgbaToHex(swatch.color) }}
                type="button"
                onClick={() => onChange(swatch.color)}
              />
            ))}
          </Box>

          {(['r', 'g', 'b'] as const).map((channel) => (
            <Box className="color-picker-channel" key={channel}>
              <Typography variant="caption">{channelLabel[channel]}</Typography>
              <Slider
                aria-label={`${label} ${channelLabel[channel]}`}
                max={255}
                min={0}
                size="small"
                step={1}
                value={color[channel]}
                onChange={(_, nextValue) => updateChannel(channel, nextValue)}
              />
              <Typography className="color-picker-channel__value" variant="caption">
                {Math.round(color[channel])}
              </Typography>
            </Box>
          ))}

          {showAlpha ? (
            <Box className="color-picker-channel">
              <Typography variant="caption">Alpha</Typography>
              <Slider
                aria-label={`${label} Alpha`}
                max={1}
                min={0}
                size="small"
                step={0.01}
                value={color.a}
                onChange={(_, nextValue) => updateAlpha(nextValue)}
              />
              <Typography className="color-picker-channel__value" variant="caption">
                {color.a.toFixed(2)}
              </Typography>
            </Box>
          ) : null}

          <TextField
            fullWidth
            error={hexDraft.length > 0 && !isHexColor(hexDraft)}
            label="Hex"
            size="small"
            value={hexDraft}
            onChange={(event) => updateHex(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 7, spellCheck: false } }}
          />
        </Stack>
      </Popover>
    </>
  );
};

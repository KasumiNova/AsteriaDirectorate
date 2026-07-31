import { useEffect, useMemo, useState } from 'react';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import {
  Alert,
  Box,
  Button,
  Chip,
  CssBaseline,
  Paper,
  Stack,
  ThemeProvider,
  Typography,
  createTheme,
} from '@mui/material';
import './App.css';
import { serializeGameTooltipExport } from './export/gameTooltipExport';
import { formatTooltipKotlinScaffold } from './export/kotlinTooltipExport';
import { createDefaultHullmodTooltipPreset } from './model/defaultHullmodPreset';
import {
  TOOLTIP_PRESET_STORAGE_VERSION,
  normalizeTooltipPreset,
  type TooltipPreset,
} from './model/tooltipPreset';
import { estimateTooltipLayout } from './render/tooltipLayout';
import { BlockEditor } from './ui/BlockEditor';
import { ShaderEditor } from './ui/ShaderEditor';
import { ThemeEditor } from './ui/ThemeEditor';
import { TooltipPreview } from './ui/TooltipPreview';

const STORAGE_KEY = 'astd-tooltip-style-editor-state';

type EditorState = {
  preset: TooltipPreset;
  importText: string;
  exportText: string;
  exportStatus: string;
};

const darkTheme = createTheme({
  palette: {
    mode: 'dark',
    primary: {
      main: '#8edfe7',
    },
    secondary: {
      main: '#a7d789',
    },
    background: {
      default: '#101415',
      paper: '#171d1f',
    },
  },
  shape: {
    borderRadius: 12,
  },
});

const loadEditorState = (): EditorState => {
  const fallback = createDefaultHullmodTooltipPreset();
  const fallbackState: EditorState = {
    preset: fallback,
    importText: '',
    exportText: '',
    exportStatus: '',
  };

  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (!stored) {
      return fallbackState;
    }
    const parsed = JSON.parse(stored);
    const storedPreset = parsed?.preset ?? parsed;
    if (storedPreset?.storageVersion !== TOOLTIP_PRESET_STORAGE_VERSION) {
      return fallbackState;
    }
    return {
      preset: normalizeTooltipPreset(storedPreset),
      importText: typeof parsed?.importText === 'string' ? parsed.importText : '',
      exportText: typeof parsed?.exportText === 'string' ? parsed.exportText : '',
      exportStatus: typeof parsed?.exportStatus === 'string' ? parsed.exportStatus : '',
    };
  } catch {
    return fallbackState;
  }
};

export const App = () => {
  const [editorState, setEditorState] = useState<EditorState>(() => loadEditorState());
  const { exportStatus, exportText, importText, preset } = editorState;
  const layout = useMemo(() => estimateTooltipLayout(preset), [preset]);
  const jsonExport = useMemo(() => serializeGameTooltipExport(preset), [preset]);
  const kotlinExport = useMemo(() => formatTooltipKotlinScaffold(preset), [preset]);

  useEffect(() => {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(editorState));
  }, [editorState]);

  const updatePreset = (nextPreset: TooltipPreset) => {
    setEditorState((current) => ({ ...current, preset: nextPreset }));
  };

  const updateImportText = (nextImportText: string) => {
    setEditorState((current) => ({ ...current, importText: nextImportText }));
  };

  const updateExportText = (nextExportText: string) => {
    setEditorState((current) => ({ ...current, exportText: nextExportText }));
  };

  const updateExportStatus = (nextExportStatus: string) => {
    setEditorState((current) => ({ ...current, exportStatus: nextExportStatus }));
  };

  const copyExport = async (label: string, text: string) => {
    updateExportText(text);

    if (!navigator.clipboard?.writeText) {
      updateExportStatus(`${label} export is shown below.`);
      return;
    }

    try {
      await navigator.clipboard.writeText(text);
      updateExportStatus(`${label} export copied.`);
    } catch {
      updateExportStatus(`${label} export is shown below.`);
    }
  };

  const importJson = () => {
    try {
      const parsed = JSON.parse(importText);
      const importedPreset = parsed?.schemaVersion === 1 ? parsed : parsed?.preset ?? parsed;
      updatePreset(normalizeTooltipPreset(importedPreset));
      updateExportText('');
      updateExportStatus('JSON import applied.');
    } catch (error) {
      updateExportStatus(error instanceof Error ? `JSON import failed: ${error.message}` : 'JSON import failed.');
    }
  };

  const resetPreset = () => {
    setEditorState({
      preset: createDefaultHullmodTooltipPreset(),
      importText: '',
      exportText: '',
      exportStatus: 'Preset reset.',
    });
  };

  return (
    <ThemeProvider theme={darkTheme}>
      <CssBaseline />
      <main className="app-shell">
        <Box component="section" className="preview-stage" aria-labelledby="preview-heading">
          <Box className="top-app-bar">
            <Box>
              <Typography className="app-kicker" variant="overline">
                ASTD tool
              </Typography>
              <Typography id="preview-heading" component="h1" variant="h5">
                Tooltip Style Editor
              </Typography>
            </Box>
            <Chip color="primary" label={`${layout.width} x ${Math.round(layout.height)}`} />
          </Box>

          <Box className="preview-stage__surface">
            <TooltipPreview preset={preset} />
          </Box>
        </Box>

        <Box component="aside" className="editor-rail" aria-label="tooltip editors">
          <Paper className="editor-panel" elevation={1} aria-labelledby="export-editor-heading">
            <Stack direction="row" spacing={2} sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
              <Typography id="export-editor-heading" variant="h6">
                Export
              </Typography>
              <Button
                color="secondary"
                size="small"
                startIcon={<RestartAltIcon />}
                variant="outlined"
                onClick={resetPreset}
              >
                Reset preset
              </Button>
            </Stack>

            <Stack direction="row" spacing={1.5} sx={{ mt: 2 }}>
              <Button
                fullWidth
                startIcon={<ContentCopyIcon />}
                variant="contained"
                onClick={() => copyExport('JSON', jsonExport)}
              >
                Copy JSON
              </Button>
              <Button
                fullWidth
                startIcon={<ContentCopyIcon />}
                variant="contained"
                onClick={() => copyExport('Kotlin scaffold', kotlinExport)}
              >
                Copy Kotlin
              </Button>
            </Stack>

            <Box sx={{ mt: 2 }}>
              <Typography component="label" htmlFor="tooltip-import-json" variant="caption">
                Import JSON
              </Typography>
              <textarea
                id="tooltip-import-json"
                name="tooltip-import-json"
                className="export-output-textarea"
                rows={5}
                value={importText}
                onChange={(event) => updateImportText(event.target.value)}
              />
              <Button fullWidth sx={{ mt: 1 }} variant="outlined" onClick={importJson}>
                Import JSON
              </Button>
            </Box>

            {exportStatus ? <Alert severity="info" sx={{ mt: 2 }}>{exportStatus}</Alert> : null}
            {exportText ? (
              <Box sx={{ mt: 2 }}>
                <Typography component="label" htmlFor="tooltip-export-output" variant="caption">
                  Export text
                </Typography>
                <textarea
                  id="tooltip-export-output"
                  name="tooltip-export-output"
                  className="export-output-textarea"
                  readOnly
                  rows={8}
                  value={exportText}
                />
              </Box>
            ) : null}
          </Paper>
          <BlockEditor preset={preset} onPresetChange={updatePreset} />
          <ShaderEditor preset={preset} onPresetChange={updatePreset} />
          <ThemeEditor preset={preset} onPresetChange={updatePreset} />
        </Box>
      </main>
    </ThemeProvider>
  );
};

export default App;

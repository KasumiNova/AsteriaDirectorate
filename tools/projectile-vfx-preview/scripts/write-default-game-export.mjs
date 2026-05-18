import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const distModule = resolve(process.cwd(), 'dist-export/gameExportCli.js');
const { createDefaultPreset, serializeGameExportPreset } = await import(pathToFileURL(distModule));

const outputPath = resolve(process.cwd(), '../../contents/data/config/astd_projectile_vfx_presets/aod7_shot.json');
mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, serializeGameExportPreset(createDefaultPreset()), 'utf8');
console.log(`wrote ${outputPath}`);

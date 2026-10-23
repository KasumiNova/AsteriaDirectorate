import type { EffectDefinition, EffectState } from './effectDefinition';
import { createDefaultParameters } from './effectDefinition';
import { BLUE_STARBURST, CRIMSON_FLARE } from './effects/starburst';
import { SHOCKWAVE_RING } from './effects/shockwave';
import { PLASMA_CORE } from './effects/plasmaCore';
import { ION_LANCE_SWEEP } from './effects/ionLance';
import { PHASE_RIFT_SLIT } from './effects/phaseRift';
import { VOID_CUTTER_BEAM } from './effects/voidCutterBeam';

export const EFFECTS: EffectDefinition[] = [
  BLUE_STARBURST,
  CRIMSON_FLARE,
  SHOCKWAVE_RING,
  PLASMA_CORE,
  ION_LANCE_SWEEP,
  PHASE_RIFT_SLIT,
  VOID_CUTTER_BEAM,
];

export function findEffect(effectId: string): EffectDefinition {
  const effect = EFFECTS.find((candidate) => candidate.id === effectId);
  if (!effect) {
    throw new Error(`Unknown effect: ${effectId}`);
  }
  return effect;
}

export function createDefaultEffectState(effectId: string = EFFECTS[0].id): EffectState {
  const effect = findEffect(effectId);
  return {
    effectId: effect.id,
    parameters: createDefaultParameters(effect.parameters),
  };
}

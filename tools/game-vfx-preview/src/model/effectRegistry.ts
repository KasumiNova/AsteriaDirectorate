/**
 * 特效注册表。
 *
 * 汇总所有可预览的特效定义，并提供按 id 查找与构造默认状态的工具。
 * UI 与渲染层只依赖此注册表，新增特效时在 {@link EFFECTS} 数组登记即可。
 */
import type { EffectDefinition, EffectState } from './effectDefinition';
import { createDefaultParameters } from './effectDefinition';
import { BLUE_STARBURST, CRIMSON_FLARE } from './effects/starburst';
import { SHOCKWAVE_RING } from './effects/shockwave';
import { PLASMA_CORE } from './effects/plasmaCore';
import { ION_LANCE_SWEEP } from './effects/ionLance';
import { PHASE_RIFT_SLIT } from './effects/phaseRift';
import { VOID_CUTTER_BEAM } from './effects/voidCutterBeam';

/** 所有可预览特效，按 UI 下拉框展示顺序排列。 */
export const EFFECTS: EffectDefinition[] = [
  BLUE_STARBURST,
  CRIMSON_FLARE,
  SHOCKWAVE_RING,
  PLASMA_CORE,
  ION_LANCE_SWEEP,
  PHASE_RIFT_SLIT,
  VOID_CUTTER_BEAM,
];

/**
 * 按 id 查找特效定义。
 *
 * @param effectId 特效 id。
 * @returns 对应的特效定义。
 * @throws id 未注册时抛出。
 */
export function findEffect(effectId: string): EffectDefinition {
  const effect = EFFECTS.find((candidate) => candidate.id === effectId);
  if (!effect) {
    throw new Error(`Unknown effect: ${effectId}`);
  }
  return effect;
}

/**
 * 构造某个特效的默认运行时状态。
 *
 * @param effectId 特效 id，缺省为注册表中的第一个特效。
 * @returns 以默认参数填充的特效状态。
 */
export function createDefaultEffectState(effectId: string = EFFECTS[0].id): EffectState {
  const effect = findEffect(effectId);
  return {
    effectId: effect.id,
    parameters: createDefaultParameters(effect.parameters),
  };
}

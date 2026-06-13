/**
 * 特效预览的核心数据模型。
 *
 * 采用数据驱动设计：每个特效是一个自包含的 {@link EffectDefinition}，
 * 携带自己的 fragment shader 与参数规格。通用渲染器与 UI 都只依赖这些类型，
 * 因此新增特效只需新增一个定义文件，无需改动渲染器或界面代码。
 */

/** 参数值字典：键为参数名，值为标量。渲染器据此上传 `u_<key>` 的 float uniform。 */
export type EffectParameters = Record<string, number>;

/**
 * 单个可调参数的规格。
 *
 * 同时驱动 MD3 滑块 UI（label / min / max / step）与 WebGL uniform
 * （key -> `u_<key>`），保证界面与着色器输入始终一致。
 */
export interface ParameterSpec {
  /** 参数键，对应 fragment shader 中的 uniform `u_<key>`。 */
  key: string;
  /** UI 中显示的标签。 */
  label: string;
  /** 滑块最小值。 */
  min: number;
  /** 滑块最大值。 */
  max: number;
  /** 滑块步进。 */
  step: number;
  /** 默认值（用于初始状态与重置）。 */
  defaultValue: number;
}

/**
 * 一个自包含的特效定义。
 *
 * fragment shader 不需要声明精度或内建 uniform（u_time / u_resolution），
 * 这些由公共 GLSL 头统一提供；着色器只声明 `parameters` 列出的私有 uniform。
 */
export interface EffectDefinition {
  /** 稳定的特效标识，用于选择与序列化。 */
  id: string;
  /** UI 中显示的特效名称。 */
  name: string;
  /** 一句话描述特效的用途与外观。 */
  description: string;
  /** 动画循环周期（秒），用于时间轴归一化。 */
  loopSeconds: number;
  /** 该特效的 fragment shader 源码（GLSL ES 1.0，不含公共头）。 */
  fragmentShader: string;
  /** 参数规格列表，按 UI 期望的展示顺序排列。 */
  parameters: ParameterSpec[];
}

/** 运行时特效状态：当前选中的特效及其参数当前值。播放进度由 playback 模型单独管理。 */
export interface EffectState {
  /** 当前选中特效的 id。 */
  effectId: string;
  /** 当前参数值。 */
  parameters: EffectParameters;
}

/**
 * 根据参数规格列表生成默认参数字典。
 *
 * @param specs 参数规格列表。
 * @returns 以每个规格的 defaultValue 填充的参数字典。
 */
export function createDefaultParameters(specs: ParameterSpec[]): EffectParameters {
  const parameters: EffectParameters = {};
  for (const spec of specs) {
    parameters[spec.key] = spec.defaultValue;
  }
  return parameters;
}

/**
 * 返回更新了单个参数后的新状态（不可变更新）。
 *
 * @param state 当前状态。
 * @param key 要更新的参数键。
 * @param value 新值。
 * @returns 新的状态对象。
 */
export function updateParameter(state: EffectState, key: string, value: number): EffectState {
  return {
    ...state,
    parameters: {
      ...state.parameters,
      [key]: value,
    },
  };
}

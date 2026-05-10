import { TimelineConfig } from '../model/preset';

export interface TimelineState {
  playing: boolean;
  timeSeconds: number;
  fps: number;
  durationSeconds: number;
}

export type TimelineAction =
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'toggle' }
  | { type: 'stepForward' }
  | { type: 'stepBackward' }
  | { type: 'seek'; timeSeconds: number }
  | { type: 'sync'; fps: number; durationSeconds: number }
  | { type: 'tick'; deltaSeconds: number; loop?: boolean };

export function createInitialTimelineState(config?: Partial<TimelineConfig>): TimelineState {
  return {
    playing: false,
    timeSeconds: 0,
    fps: config?.fps ?? 60,
    durationSeconds: config?.durationSeconds ?? 1.25,
  };
}

export function timelineReducer(state: TimelineState, action: TimelineAction): TimelineState {
  switch (action.type) {
    case 'play':
      if (state.timeSeconds >= state.durationSeconds) {
        return { ...state, playing: true, timeSeconds: 0 };
      }
      return { ...state, playing: true };
    case 'pause':
      return { ...state, playing: false };
    case 'toggle':
      if (!state.playing && state.timeSeconds >= state.durationSeconds) {
        return { ...state, playing: true, timeSeconds: 0 };
      }
      return { ...state, playing: !state.playing };
    case 'stepForward':
      return { ...state, timeSeconds: clampTime(state, state.timeSeconds + 1 / state.fps) };
    case 'stepBackward':
      return { ...state, timeSeconds: clampTime(state, state.timeSeconds - 1 / state.fps) };
    case 'seek':
      return { ...state, timeSeconds: clampTime(state, action.timeSeconds) };
    case 'sync': {
      const next = {
        ...state,
        fps: action.fps,
        durationSeconds: action.durationSeconds,
      };
      return {
        ...next,
        timeSeconds: clampTime(next, next.timeSeconds),
      };
    }
    case 'tick': {
      const duration = Math.max(state.durationSeconds, 0.0001);
      const next = state.timeSeconds + action.deltaSeconds;
      if (action.loop && next > duration) {
        return { ...state, timeSeconds: next % duration };
      }
      if (next >= duration) {
        return { ...state, playing: false, timeSeconds: duration };
      }
      return { ...state, timeSeconds: clampTime(state, next) };
    }
    default:
      return state;
  }
}

export function getCurrentFrame(state: TimelineState): number {
  return Math.round(state.timeSeconds * state.fps);
}

function clampTime(state: TimelineState, timeSeconds: number): number {
  return Math.min(state.durationSeconds, Math.max(0, timeSeconds));
}

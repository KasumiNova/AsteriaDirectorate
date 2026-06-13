export interface PlaybackState {
  durationSeconds: number;
  elapsedSeconds: number;
  playing: boolean;
}

export type PlaybackAction =
  | { type: 'setPlaying'; playing: boolean }
  | { type: 'seek'; elapsedSeconds: number }
  | { type: 'setDuration'; durationSeconds: number }
  | { type: 'tick'; deltaSeconds: number };

export function createPlaybackState(durationSeconds: number): PlaybackState {
  return {
    durationSeconds,
    elapsedSeconds: 0,
    playing: true,
  };
}

export function playbackReducer(state: PlaybackState, action: PlaybackAction): PlaybackState {
  switch (action.type) {
    case 'setPlaying':
      return { ...state, playing: action.playing };
    case 'seek':
      return {
        ...state,
        elapsedSeconds: clamp(action.elapsedSeconds, 0, state.durationSeconds),
        playing: false,
      };
    case 'setDuration': {
      const durationSeconds = Math.max(0.001, action.durationSeconds);
      return {
        ...state,
        durationSeconds,
        elapsedSeconds: clamp(state.elapsedSeconds, 0, durationSeconds),
      };
    }
    case 'tick': {
      if (!state.playing) {
        return state;
      }
      return {
        ...state,
        elapsedSeconds: wrapTime(state.elapsedSeconds + action.deltaSeconds, state.durationSeconds),
      };
    }
  }
}

function wrapTime(timeSeconds: number, durationSeconds: number): number {
  const duration = Math.max(0.001, durationSeconds);
  return ((timeSeconds % duration) + duration) % duration;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

import { playbackReducer, createPlaybackState } from './playback';

describe('playbackReducer', () => {
  it('advances time while playing and wraps by duration', () => {
    const state = createPlaybackState(1.45);

    expect(playbackReducer(state, { type: 'tick', deltaSeconds: 0.5 }).elapsedSeconds).toBe(0.5);
    expect(playbackReducer({ ...state, elapsedSeconds: 1.3 }, { type: 'tick', deltaSeconds: 0.3 }).elapsedSeconds).toBeCloseTo(0.15);
  });

  it('does not advance while paused', () => {
    const state = { ...createPlaybackState(1.45), playing: false, elapsedSeconds: 0.4 };

    expect(playbackReducer(state, { type: 'tick', deltaSeconds: 0.5 })).toEqual(state);
  });

  it('seeks to a fixed frame and pauses playback', () => {
    const state = createPlaybackState(1.45);

    expect(playbackReducer(state, { type: 'seek', elapsedSeconds: 0.32 })).toEqual({
      durationSeconds: 1.45,
      elapsedSeconds: 0.32,
      playing: false,
    });
  });
});

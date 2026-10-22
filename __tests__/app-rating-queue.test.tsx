import { act, renderHook } from '@testing-library/react-native-legacy';
import { useAppRating } from '../src/ui/useAppRating';

it('serializes writes and persists the final star crossed while an earlier write is pending', async () => {
  let finish!: () => void;
  const save = jest.fn().mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve; })).mockResolvedValue(undefined);
  const refresh = jest.fn().mockResolvedValue(undefined), onError = jest.fn();
  const { result } = renderHook(() => useAppRating(save, refresh, onError));
  act(() => { result.current(1); result.current(3); result.current(5); });
  expect(save.mock.calls).toEqual([[1]]);
  await act(async () => { finish(); await Promise.resolve(); await Promise.resolve(); });
  expect(save.mock.calls).toEqual([[1], [5]]);
  expect(refresh).toHaveBeenCalledTimes(1); expect(onError).not.toHaveBeenCalled();
});
it('recovers from failed writes, refreshes authoritative state and accepts another rating', async () => {
  const save = jest.fn().mockRejectedValueOnce(new Error('storage')).mockResolvedValue(undefined);
  const refresh = jest.fn().mockRejectedValueOnce(new Error('read')).mockResolvedValue(undefined), onError = jest.fn();
  const { result } = renderHook(() => useAppRating(save, refresh, onError));
  await act(async () => { result.current(2); await Promise.resolve(); await Promise.resolve(); });
  expect(onError).toHaveBeenCalledTimes(2);
  await act(async () => { result.current(4); await Promise.resolve(); await Promise.resolve(); });
  expect(save.mock.calls).toEqual([[2], [4]]); expect(refresh).toHaveBeenCalledTimes(2);
});
it('drops queued work and UI notifications after unmount', async () => {
  let reject!: (error: Error) => void;
  const save = jest.fn(() => new Promise<void>((_, failure) => { reject = failure; }));
  const refresh = jest.fn().mockResolvedValue(undefined), onError = jest.fn();
  const { result, unmount } = renderHook(() => useAppRating(save, refresh, onError));
  const rate = result.current;
  act(() => { rate(1); rate(5); }); unmount();
  await act(async () => { reject(new Error('late')); await Promise.resolve(); });
  rate(3); expect(save).toHaveBeenCalledTimes(1); expect(refresh).not.toHaveBeenCalled(); expect(onError).not.toHaveBeenCalled();
});

it('drains a final rating selected while authoritative refresh is pending', async () => {
  let finishRefresh!: () => void;
  const save = jest.fn().mockResolvedValue(undefined);
  const refresh = jest.fn().mockImplementationOnce(() => new Promise<void>(resolve => { finishRefresh = resolve; })).mockResolvedValue(undefined);
  const onError = jest.fn();
  const { result } = renderHook(() => useAppRating(save, refresh, onError));
  await act(async () => { result.current(1); await Promise.resolve(); });
  act(() => { result.current(5); });
  await act(async () => { finishRefresh(); await Promise.resolve(); await Promise.resolve(); });
  expect(save.mock.calls).toEqual([[1], [5]]);
  expect(refresh).toHaveBeenCalledTimes(2); expect(onError).not.toHaveBeenCalled();
});

it('does not report a late refresh failure to an unmounted screen', async () => {
  let reject!: (error: Error) => void;
  const save = jest.fn().mockResolvedValue(undefined);
  const refresh = jest.fn(() => new Promise<void>((_resolve, failure) => { reject = failure; }));
  const onError = jest.fn();
  const { result, unmount } = renderHook(() => useAppRating(save, refresh, onError));
  await act(async () => { result.current(3); await Promise.resolve(); });
  expect(refresh).toHaveBeenCalledTimes(1);
  unmount();
  await act(async () => { reject(new Error('late read')); await Promise.resolve(); });
  expect(onError).not.toHaveBeenCalled();
});

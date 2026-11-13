import { useState, useEffect, useRef, useCallback } from 'react';

/**
 * usePolling executes an async loader and polls with a timeout chain while shouldContinue(data) is true.
 * Respects document visibility (pauses while hidden) and stops after 3 consecutive errors.
 * @template T
 * @param {() => Promise<T>} asyncFn
 * @param {number} intervalMs - Polling interval between answers
 * @param {(data: T) => boolean} shouldContinue - Predicate to decide if polling should continue
 * @param {Array<any>} [deps=[]]
 * @returns {{ data: T|null, loading: boolean, error: Error|null, reload: () => Promise<void> }}
 */
export function usePolling(asyncFn, intervalMs, shouldContinue, deps = []) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const fnRef = useRef(asyncFn);
  const shouldContinueRef = useRef(shouldContinue);
  const timeoutIdRef = useRef(null);
  const consecutiveErrorsRef = useRef(0);
  const activeRef = useRef(true);

  useEffect(() => {
    fnRef.current = asyncFn;
    shouldContinueRef.current = shouldContinue;
  });

  const clearTimer = () => {
    if (timeoutIdRef.current) {
      clearTimeout(timeoutIdRef.current);
      timeoutIdRef.current = null;
    }
  };

  const pollRef = useRef(null);

  const executeOnce = useCallback(async () => {
    clearTimer();
    try {
      const result = await fnRef.current();
      if (!activeRef.current) return result;

      consecutiveErrorsRef.current = 0;
      setData(result);
      setError(null);
      setLoading(false);

      if (shouldContinueRef.current(result)) {
        if (!document.hidden) {
          timeoutIdRef.current = setTimeout(() => {
            if (activeRef.current && pollRef.current) {
              pollRef.current();
            }
          }, intervalMs);
        }
      }
      return result;
    } catch (err) {
      if (!activeRef.current) return;
      consecutiveErrorsRef.current += 1;
      setError(err);
      setLoading(false);

      // Retry up to 3 consecutive errors
      if (consecutiveErrorsRef.current < 3) {
        if (!document.hidden) {
          timeoutIdRef.current = setTimeout(() => {
            if (activeRef.current && pollRef.current) {
              pollRef.current();
            }
          }, intervalMs);
        }
      }
    }
  }, [intervalMs]);

  useEffect(() => {
    pollRef.current = executeOnce;
  });

  useEffect(() => {
    activeRef.current = true;
    consecutiveErrorsRef.current = 0;
    setLoading(true);
    setError(null);

    executeOnce();

    const handleVisibilityChange = () => {
      if (!document.hidden && activeRef.current) {
        executeOnce();
      } else {
        clearTimer();
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      activeRef.current = false;
      clearTimer();
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, deps);

  return {
    data,
    loading,
    error,
    reload: executeOnce,
  };
}

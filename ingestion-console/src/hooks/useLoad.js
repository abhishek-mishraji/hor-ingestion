import { useState, useEffect, useCallback, useRef } from 'react';

/**
 * Hook to manage loading state, data, error, and refetching for async data.
 * @template T
 * @param {() => Promise<T>} asyncFn - Async loader function
 * @param {Array<any>} [deps=[]] - Dependency array that triggers reload
 * @returns {{ data: T|null, loading: boolean, error: Error|null, reload: () => Promise<void>, setData: React.Dispatch<React.SetStateAction<T|null>> }}
 */
export function useLoad(asyncFn, deps = []) {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Keep latest function reference
  const fnRef = useRef(asyncFn);
  useEffect(() => {
    fnRef.current = asyncFn;
  });

  const execute = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fnRef.current();
      setData(result);
    } catch (err) {
      setError(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let active = true;

    setLoading(true);
    setError(null);

    fnRef.current()
      .then((res) => {
        if (active) {
          setData(res);
          setLoading(false);
        }
      })
      .catch((err) => {
        if (active) {
          setError(err);
          setLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, deps);

  return {
    data,
    loading,
    error,
    reload: execute,
    setData,
  };
}

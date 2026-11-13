import { useState, useCallback } from 'react';

/**
 * Hook for executing mutating actions (POST, PUT, PATCH, DELETE) with loading and error states.
 * @template TArgs, TResult
 * @param {(...args: TArgs[]) => Promise<TResult>} actionFn
 * @returns {{ execute: (...args: TArgs[]) => Promise<TResult>, loading: boolean, error: Error|null, clearError: () => void, success: boolean }}
 */
export function useAction(actionFn) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(false);

  const clearError = useCallback(() => {
    setError(null);
  }, []);

  const execute = useCallback(async (...args) => {
    setLoading(true);
    setError(null);
    setSuccess(false);
    try {
      const result = await actionFn(...args);
      setSuccess(true);
      return result;
    } catch (err) {
      setError(err);
      throw err;
    } finally {
      setLoading(false);
    }
  }, [actionFn]);

  return {
    execute,
    loading,
    error,
    clearError,
    success,
  };
}

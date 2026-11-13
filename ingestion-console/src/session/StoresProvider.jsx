import { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import { useVendor } from '../vendors/VendorProvider.jsx';

/**
 * @typedef {import('../api/ingestionApi.js').Store} Store
 */

/**
 * @typedef {object} StoresContextType
 * @property {Store[]} stores
 * @property {Map<number, Store>} byId
 * @property {boolean} loading
 * @property {Error|null} error
 * @property {() => Promise<void>} reload
 */

const StoresContext = createContext(/** @type {StoresContextType|null} */ (null));

/**
 * StoresProvider caches the list of stores for the currently active vendor.
 * @param {object} props
 * @param {React.ReactNode} props.children
 */
export function StoresProvider({ children }) {
  const { vendor, api } = useVendor();
  const [stores, setStores] = useState(/** @type {Store[]} */ ([]));
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  const fetchStores = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.listStores();
      setStores(Array.isArray(data) ? data : []);
    } catch (err) {
      setError(err);
      setStores([]);
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    fetchStores();
  }, [fetchStores, vendor]);

  const byId = useMemo(() => {
    const map = new Map();
    for (const s of stores) {
      map.set(s.storeId, s);
    }
    return map;
  }, [stores]);

  const value = {
    stores,
    byId,
    loading,
    error,
    reload: fetchStores,
  };

  return (
    <StoresContext.Provider value={value}>
      {children}
    </StoresContext.Provider>
  );
}

/**
 * Hook to consume cached stores list.
 * @returns {StoresContextType}
 */
export function useStores() {
  const context = useContext(StoresContext);
  if (!context) {
    throw new Error('useStores must be used within StoresProvider');
  }
  return context;
}

import { createContext, useContext, useMemo, useEffect } from 'react';
import { useParams } from 'react-router';
import { VENDORS, isVendor, setLastVendor } from './vendors.js';
import { apiFor } from '../api/ingestionApi.js';
import { NotFoundPage } from '../pages/NotFoundPage.jsx';

/**
 * @typedef {object} VendorContextType
 * @property {'verifone'|'gilbarco'} vendor
 * @property {import('./vendors.js').VendorConfig} config
 * @property {import('../api/ingestionApi.js').IngestionApi} api
 */

const VendorContext = createContext(/** @type {VendorContextType|null} */ (null));

/**
 * VendorProvider reads the :vendor route param and provides the active vendor, config, and API adapter.
 * If the vendor route param is invalid, it renders NotFoundPage.
 * @param {object} props
 * @param {React.ReactNode} props.children
 */
export function VendorProvider({ children }) {
  const { vendor: vendorParam } = useParams();

  const valid = isVendor(vendorParam);
  const vendor = valid ? /** @type {'verifone'|'gilbarco'} */ (vendorParam) : null;

  useEffect(() => {
    if (vendor) {
      setLastVendor(vendor);
    }
  }, [vendor]);

  const config = vendor ? VENDORS[vendor] : null;
  const api = useMemo(() => (vendor ? apiFor(vendor) : null), [vendor]);

  const value = useMemo(() => {
    if (!vendor || !config || !api) return null;
    return { vendor, config, api };
  }, [vendor, config, api]);

  if (!valid || !value) {
    return <NotFoundPage />;
  }

  return (
    <VendorContext.Provider value={value}>
      {children}
    </VendorContext.Provider>
  );
}

/**
 * Hook to consume the current vendor context.
 * @returns {VendorContextType}
 */
export function useVendor() {
  const context = useContext(VendorContext);
  if (!context) {
    throw new Error('useVendor must be used within VendorProvider');
  }
  return context;
}

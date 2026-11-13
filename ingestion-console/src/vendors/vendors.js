/**
 * @file vendors.js
 * Vendor registry defining POS vendor configurations and metadata.
 */

/**
 * @typedef {object} VendorConfig
 * @property {'verifone'|'gilbarco'} id
 * @property {string} label
 * @property {number} port
 * @property {string} service
 * @property {boolean} hasFileTypes
 * @property {boolean} hasDeptHistory
 * @property {boolean} canEditDepartments a platform admin may set a department's name and sales class
 */

/** @type {Record<'verifone'|'gilbarco', VendorConfig>} */
export const VENDORS = {
  verifone: {
    id: 'verifone',
    label: 'Verifone',
    port: 8092,
    service: 'verifone-ingestion-service',
    hasFileTypes: false,
    hasDeptHistory: true,
    canEditDepartments: false,
  },
  gilbarco: {
    id: 'gilbarco',
    label: 'Gilbarco',
    port: 8093,
    service: 'gilbarco-ingestion-service',
    hasFileTypes: true,
    hasDeptHistory: false,
    canEditDepartments: true,
  },
};

export const DEFAULT_VENDOR = 'verifone';

/**
 * Checks if a string is a recognized vendor ID.
 * @param {string|null|undefined} v
 * @returns {boolean}
 */
export const isVendor = (v) => typeof v === 'string' && Object.hasOwn(VENDORS, v);

/**
 * Retrieves the last selected vendor from localStorage, falling back to DEFAULT_VENDOR.
 * @returns {'verifone'|'gilbarco'}
 */
export function getLastVendor() {
  try {
    const saved = localStorage.getItem('hor-ingestion-vendor');
    if (saved && isVendor(saved)) {
      return /** @type {'verifone'|'gilbarco'} */ (saved);
    }
  } catch {
    // localStorage may be disabled or restricted
  }
  return DEFAULT_VENDOR;
}

/**
 * Persists the chosen vendor to localStorage.
 * @param {string} vendor
 */
export function setLastVendor(vendor) {
  try {
    if (isVendor(vendor)) {
      localStorage.setItem('hor-ingestion-vendor', vendor);
    }
  } catch {
    // ignore storage errors
  }
}

/**
 * @file ingestionApi.js
 * Normalized Ingestion API layer providing a unified interface across POS vendors (Verifone, Gilbarco).
 * Pages interact solely with this normalized adapter layer.
 */

import * as verifoneApi from './verifoneApi.js';
import * as gilbarcoApi from './gilbarcoApi.js';

/**
 * @typedef {import('./verifoneApi.js').Store} Store
 * @typedef {import('./verifoneApi.js').SyncResult} SyncResult
 * @typedef {{businessDate:string, versionNo:number, files:number, loadedAt:string,
 *             fetchRequestId?:number, s3ObjectId?:number, daySeqs?:number[]}} NormalizedLoadedDay
 * @typedef {{name:string, expected?:number, actual?:number, passed:boolean, hard:boolean, detail?:string}} LoadCheck
 * @typedef {{title:string, filePath?:string, daySeq?:number, loadedAt?:string,
 *             rows:Record<string,number>, checks:LoadCheck[]}} NormalizedSection
 * @typedef {{id:number, storeId:number, businessDate:string, fileType:string, mode:'REFUSE'|'REPLACE',
 *             status:string, message?:string, requestedBy:number, requestedAt:string,
 *             finishedAt?:string, durationMs?:number}} NormalizedFetch
 * @typedef {{bucket:string, key:string, fileType?:string, version:number, etag:string, sizeBytes:number,
 *             lastModified:string, manifestCreatedAt?:string, mirrorVersion?:string, files?:number}} NormalizedZip
 * @typedef {{fetch:NormalizedFetch, zip?:NormalizedZip, sections:NormalizedSection[]}} NormalizedFetchDetail
 * @typedef {{items:NormalizedFetch[], total:number, limit:number, offset:number}} NormalizedFetchPage
 * @typedef {{name:string, deptType?:string, firstSeen?:string, lastSeen?:string}} DeptHistoryName
 * @typedef {{deptNumber:number, name:string|null, salesClass:'merchandise'|'lottery_sale'|'lottery_payout'|'fuel',
 *             history?:DeptHistoryName[], posName?:string|null, adminManaged?:boolean, adminSetBy?:number|null,
 *             adminSetAt?:string|null, lastSold?:string|null}} NormalizedDepartment
 * @typedef {import('./gilbarcoApi.js').GilbarcoDepartmentChange} DepartmentChange
 * @typedef {import('./gilbarcoApi.js').GilbarcoDepartmentItems} DepartmentItems
 */

/**
 * Checks if a fetch status is in an open/in-progress state (queued or executing).
 * @param {string|null|undefined} status
 * @returns {boolean}
 */
export function isOpen(status) {
  return status === 'CLAIMED' || status === 'RUNNING';
}

/**
 * @typedef {object} IngestionApi
 * @property {() => Promise<string[]>} listFileTypes
 * @property {() => Promise<Store[]>} listStores
 * @property {() => Promise<SyncResult>} syncStores
 * @property {(storeId: number|string, year: number, month: number, fileType?: string) => Promise<string[]>} availableDays
 * @property {(storeId: number|string, from: string, to: string, fileType?: string) => Promise<NormalizedLoadedDay[]>} loadedDays
 * @property {(params: {storeId: number|string, businessDate: string, mode: 'REFUSE'|'REPLACE', fileType?: string}) => Promise<{fetchRequestId: number}>} startFetch
 * @property {(id: number|string) => Promise<NormalizedFetchDetail>} getFetch
 * @property {(params?: {storeId?: number|string, from?: string, to?: string, fileType?: string, limit?: number, offset?: number}) => Promise<NormalizedFetchPage>} listFetches
 * @property {(storeId: number|string) => Promise<NormalizedDepartment[]>} listDepartments
 * @property {(storeId: number|string, deptNumber: number, body: {name: string, salesClass: string, note?: string}) => Promise<NormalizedDepartment>} [setDepartment]
 *           Only where the vendor config has canEditDepartments.
 * @property {(storeId: number|string, deptNumber: number) => Promise<NormalizedDepartment>} [resetDepartment]
 * @property {(storeId: number|string, deptNumber: number) => Promise<DepartmentChange[]>} [departmentChanges]
 * @property {(storeId: number|string, deptNumber: number, limit?: number) => Promise<DepartmentItems>} [departmentItems]
 */

/** @type {IngestionApi} */
const verifoneAdapter = {
  async listFileTypes() {
    return ['CPJ'];
  },

  async listStores() {
    return verifoneApi.listStores();
  },

  async syncStores() {
    return verifoneApi.syncStores();
  },

  async availableDays(storeId, year, month) {
    return verifoneApi.getAvailableDays(Number(storeId), year, month);
  },

  async loadedDays(storeId, from, to) {
    return verifoneApi.getLoadedDays(Number(storeId), from, to);
  },

  async startFetch({ storeId, businessDate, mode }) {
    return verifoneApi.startFetch({ storeId: Number(storeId), businessDate, mode });
  },

  async getFetch(id) {
    const res = await verifoneApi.getFetch(id);
    const sections = (res.files || []).map((f) => ({
      title: f.filePath ? `${f.filePath} (day seq ${f.daySeq})` : `Day seq ${f.daySeq}`,
      filePath: f.filePath,
      daySeq: f.daySeq,
      loadedAt: f.loadedAt,
      rows: f.rows || {},
      checks: f.checks || [],
    }));
    return {
      fetch: { ...res.fetch, fileType: 'CPJ' },
      zip: res.zip,
      sections,
    };
  },

  async listFetches(params = {}) {
    const res = await verifoneApi.listFetches(params);
    return {
      ...res,
      items: (res.items || []).map((it) => ({
        ...it,
        fileType: 'CPJ',
      })),
    };
  },

  async listDepartments(storeId) {
    const res = await verifoneApi.listDepartments(storeId);
    return (res || []).map((d) => {
      let salesClass = 'merchandise';
      if (d.lotteryClass === 'LOTTERY_SALE') salesClass = 'lottery_sale';
      else if (d.lotteryClass === 'LOTTERY_PAYOUT') salesClass = 'lottery_payout';
      return {
        deptNumber: d.deptNumber,
        name: d.currentName ?? null,
        salesClass,
        history: d.names || [],
      };
    });
  },
};

/** @type {IngestionApi} */
const gilbarcoAdapter = {
  async listFileTypes() {
    const types = await gilbarcoApi.listFileTypes();
    return [...types]
      .sort((a, b) => (b.isDefault ? 1 : 0) - (a.isDefault ? 1 : 0))
      .map((t) => t.fileType);
  },

  async listStores() {
    return gilbarcoApi.listStores();
  },

  async syncStores() {
    return gilbarcoApi.syncStores();
  },

  async availableDays(storeId, year, month, fileType = 'PJR') {
    return gilbarcoApi.getAvailableDays(storeId, year, month, fileType);
  },

  async loadedDays(storeId, from, to, fileType = 'PJR') {
    return gilbarcoApi.getLoadedDays(storeId, from, to, fileType);
  },

  async startFetch({ storeId, businessDate, mode, fileType = 'PJR' }) {
    return gilbarcoApi.startFetch({ storeId, businessDate, mode, fileType });
  },

  async getFetch(id) {
    const res = await gilbarcoApi.getFetch(id);
    const hasRows = res.rows && Object.keys(res.rows).length > 0;
    const hasChecks = Array.isArray(res.checks) && res.checks.length > 0;
    const sections = [];

    if (hasRows || hasChecks) {
      const ft = res.fetch.fileType || 'PJR';
      const countStr = res.zip?.files != null ? ` (${res.zip.files} files)` : '';
      sections.push({
        title: `${ft} day package${countStr}`,
        rows: res.rows || {},
        checks: res.checks || [],
      });
    }

    return {
      fetch: res.fetch,
      zip: res.zip,
      sections,
    };
  },

  async listFetches(params = {}) {
    return gilbarcoApi.listFetches(params);
  },

  async listDepartments(storeId) {
    const res = await gilbarcoApi.listDepartments(storeId);
    return (res || []).map(normalizeGilbarcoDepartment);
  },

  async setDepartment(storeId, deptNumber, body) {
    return normalizeGilbarcoDepartment(await gilbarcoApi.setDepartment(storeId, deptNumber, body));
  },

  async resetDepartment(storeId, deptNumber) {
    return normalizeGilbarcoDepartment(await gilbarcoApi.resetDepartment(storeId, deptNumber));
  },

  async departmentChanges(storeId, deptNumber) {
    return (await gilbarcoApi.listDepartmentChanges(storeId, deptNumber)) || [];
  },

  async departmentItems(storeId, deptNumber, limit) {
    return gilbarcoApi.listDepartmentItems(storeId, deptNumber, limit);
  },
};

/**
 * @param {import('./gilbarcoApi.js').GilbarcoDepartment} d
 * @returns {NormalizedDepartment}
 */
function normalizeGilbarcoDepartment(d) {
  return {
    deptNumber: d.deptNumber,
    name: d.deptName ?? null,
    salesClass: d.salesClass || 'merchandise',
    history: undefined,
    posName: d.posName ?? null,
    adminManaged: Boolean(d.adminManaged),
    adminSetBy: d.adminSetBy ?? null,
    adminSetAt: d.adminSetAt ?? null,
    lastSold: d.lastSold ?? null,
  };
}

/**
 * Returns the normalized Ingestion API adapter for the specified vendor.
 * @param {'verifone'|'gilbarco'|string} vendor
 * @returns {IngestionApi}
 */
export function apiFor(vendor) {
  if (vendor === 'gilbarco') return gilbarcoAdapter;
  return verifoneAdapter;
}

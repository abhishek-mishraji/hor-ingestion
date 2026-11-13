/**
 * @file gilbarcoApi.js
 * Gilbarco Ingestion API client calling gilbarco-ingestion-service through Vite proxy (/api/ingestion/gilbarco).
 */

import { request, query } from './client.js';

/** @typedef {{storeId:number, storeCode:string, storeName:string, s3Folder:string, timezone:string,
 *             status:'ACTIVE'|'INACTIVE', syncedAt:string}} Store */
/** @typedef {{inserted:number, updated:number, deactivated:number, unchanged:number,
 *             conflicts:Array<{storeId:number, storeCode:string, reason:string}>}} SyncResult */
/** @typedef {{fileType:string, isDefault:boolean}} FileType */
/** @typedef {{businessDate:string, versionNo:number, files:number, loadedAt:string,
 *             s3ObjectId:number}} GilbarcoLoadedDay */
/** @typedef {'CLAIMED'|'RUNNING'|'LOADED'|'REFUSED'|'NOT_FOUND'|'FAILED'} GilbarcoFetchStatus */
/** @typedef {{id:number, storeId:number, businessDate:string, fileType:string, mode:'REFUSE'|'REPLACE',
 *             status:GilbarcoFetchStatus, message?:string, requestedBy:number, requestedAt:string,
 *             finishedAt?:string, durationMs?:number}} GilbarcoFetch */
/** @typedef {{bucket:string, key:string, fileType:string, version:number, etag:string, sizeBytes:number,
 *             lastModified:string, manifestCreatedAt?:string, mirrorVersion?:string, files:number}} GilbarcoZip */
/** @typedef {{name:string, expected?:number, actual?:number, passed:boolean, hard:boolean, detail?:string}} GilbarcoLoadCheck */
/** @typedef {{fetch:GilbarcoFetch, zip?:GilbarcoZip, rows?:Record<string,number>, checks?:GilbarcoLoadCheck[]}} GilbarcoFetchDetail */
/** @typedef {{items:GilbarcoFetch[], total:number, limit:number, offset:number}} GilbarcoFetchPage */
/** @typedef {{deptNumber:number, deptName?:string, posName?:string, salesClass:'merchandise'|'lottery_sale'|'lottery_payout'|'fuel',
 *             adminManaged:boolean, adminSetBy?:number, adminSetAt?:string, lastSold?:string, updatedAt:string}} GilbarcoDepartment */
/** @typedef {{changeId:number, action:'SET'|'RESET', oldName?:string, oldSalesClass:string, newName?:string,
 *             newSalesClass:string, note?:string, changedBy:number, changedAt:string}} GilbarcoDepartmentChange */
/** @typedef {{posCode?:string, description?:string, lineType:string, units:number, amount:number, lines:number,
 *             lastSold:string}} GilbarcoDepartmentItem */
/** @typedef {{items:GilbarcoDepartmentItem[], distinctItems:number, lines:number, amount:number}} GilbarcoDepartmentItems */

const BASE = '/api/ingestion/gilbarco';

/**
 * Returns supported file types for Gilbarco.
 * @returns {Promise<FileType[]>}
 */
export async function listFileTypes() {
  return request(`${BASE}/file-types`);
}

/**
 * Returns all Gilbarco stores known to gilbarco_db.
 * @returns {Promise<Store[]>}
 */
export async function listStores() {
  return request(`${BASE}/stores`);
}

/**
 * Copies the GILBARCO stores of auth_db into master.stores.
 * @returns {Promise<SyncResult>}
 */
export async function syncStores() {
  return request(`${BASE}/stores/sync`, {
    method: 'POST',
  });
}

/**
 * Lists days in a given month that have a zip in S3 for the specified file type.
 * @param {number|string} storeId
 * @param {number} year
 * @param {number} month (1..12)
 * @param {string} [fileType='PJR']
 * @returns {Promise<string[]>}
 */
export async function getAvailableDays(storeId, year, month, fileType = 'PJR') {
  return request(`${BASE}/stores/${storeId}/available-days${query({ year, month, fileType })}`);
}

/**
 * Lists business days already loaded for a store within a date range and file type.
 * @param {number|string} storeId
 * @param {string} from YYYY-MM-DD
 * @param {string} to YYYY-MM-DD
 * @param {string} [fileType='PJR']
 * @returns {Promise<GilbarcoLoadedDay[]>}
 */
export async function getLoadedDays(storeId, from, to, fileType = 'PJR') {
  return request(`${BASE}/stores/${storeId}/loaded-days${query({ from, to, fileType })}`);
}

/**
 * Starts a background fetch for a store, business day, and file type.
 * @param {{storeId: number|string, businessDate: string, mode: 'REFUSE'|'REPLACE', fileType?: string}} params
 * @returns {Promise<{fetchRequestId: number}>}
 */
export async function startFetch({ storeId, businessDate, mode, fileType = 'PJR' }) {
  return request(`${BASE}/fetches`, {
    method: 'POST',
    body: JSON.stringify({ storeId: Number(storeId), businessDate, mode, fileType }),
  });
}

/**
 * Gets detail and verification checks of a Gilbarco fetch request.
 * @param {number|string} id
 * @returns {Promise<GilbarcoFetchDetail>}
 */
export async function getFetch(id) {
  return request(`${BASE}/fetches/${id}`);
}

/**
 * Lists historical Gilbarco fetch requests with optional filters and paging.
 * @param {{storeId?: number|string, from?: string, to?: string, fileType?: string, limit?: number, offset?: number}} [params]
 * @returns {Promise<GilbarcoFetchPage>}
 */
export async function listFetches(params = {}) {
  return request(`${BASE}/fetches${query(params)}`);
}

/**
 * Lists all departments and current sales class for a store.
 * @param {number|string} storeId
 * @returns {Promise<GilbarcoDepartment[]>}
 */
export async function listDepartments(storeId) {
  return request(`${BASE}/stores/${storeId}/departments`);
}

/**
 * Sets a department's name and sales class (platform admin). They then stay whatever the POS files say.
 * @param {number|string} storeId
 * @param {number} deptNumber
 * @param {{name: string, salesClass: 'merchandise'|'lottery_sale'|'lottery_payout', note?: string}} body
 * @returns {Promise<GilbarcoDepartment>}
 */
export async function setDepartment(storeId, deptNumber, { name, salesClass, note }) {
  return request(`${BASE}/stores/${storeId}/departments/${deptNumber}`, {
    method: 'PUT',
    body: JSON.stringify({ name, salesClass, note: note || undefined }),
  });
}

/**
 * Returns a department to the POS name and the class that name gives.
 * @param {number|string} storeId
 * @param {number} deptNumber
 * @returns {Promise<GilbarcoDepartment>}
 */
export async function resetDepartment(storeId, deptNumber) {
  return request(`${BASE}/stores/${storeId}/departments/${deptNumber}/override`, { method: 'DELETE' });
}

/**
 * The admin changes of one department, newest first.
 * @param {number|string} storeId
 * @param {number} deptNumber
 * @returns {Promise<GilbarcoDepartmentChange[]>}
 */
export async function listDepartmentChanges(storeId, deptNumber) {
  return request(`${BASE}/stores/${storeId}/departments/${deptNumber}/changes`);
}

/**
 * What was sold in a department (lines not cancelled, all loaded days): the items with the most lines, and totals.
 * @param {number|string} storeId
 * @param {number} deptNumber
 * @param {number} [limit=25] 1..100
 * @returns {Promise<GilbarcoDepartmentItems>}
 */
export async function listDepartmentItems(storeId, deptNumber, limit = 25) {
  return request(`${BASE}/stores/${storeId}/departments/${deptNumber}/items${query({ limit })}`);
}

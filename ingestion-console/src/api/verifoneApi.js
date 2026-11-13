/**
 * @file verifoneApi.js
 * Verifone Ingestion API client calling verifone-ingestion-service through Vite proxy (/api/ingestion/verifone).
 */

import { request, query } from './client.js';

/** @typedef {{storeId:number, storeCode:string, storeName:string, s3Folder:string, timezone:string,
 *             status:'ACTIVE'|'INACTIVE', syncedAt:string}} Store */
/** @typedef {{inserted:number, updated:number, deactivated:number, unchanged:number,
 *             conflicts:Array<{storeId:number, storeCode:string, reason:string}>}} SyncResult */
/** @typedef {{businessDate:string, versionNo:number, files:number, daySeqs:number[], loadedAt:string,
 *             fetchRequestId:number}} LoadedDay */
/** @typedef {'RUNNING'|'LOADED'|'REFUSED'|'NOT_FOUND'|'FAILED'} FetchStatus */
/** @typedef {{id:number, storeId:number, businessDate:string, mode:'REFUSE'|'REPLACE', status:FetchStatus,
 *             message?:string, requestedBy:number, requestedAt:string, finishedAt?:string, durationMs?:number}} Fetch */
/** @typedef {{bucket:string, key:string, version:number, etag:string, sizeBytes:number, lastModified:string,
 *             manifestCreatedAt?:string, mirrorVersion?:string}} Zip */
/** @typedef {{name:string, expected?:number, actual?:number, passed:boolean, hard:boolean, detail?:string}} LoadCheck */
/** @typedef {{cpjFileId:number, filePath:string, daySeq:number, loadedAt:string,
 *             rows:Object<string,number>, checks:LoadCheck[]}} CpjFile */
/** @typedef {{fetch:Fetch, zip?:Zip, files?:CpjFile[]}} FetchDetail */
/** @typedef {{items:Fetch[], total:number, limit:number, offset:number}} FetchPage */
/** @typedef {{name:string, deptType?:string, firstSeen?:string, lastSeen?:string}} DeptName */
/** @typedef {{deptNumber:number, currentName:string, names:DeptName[],
 *             lotteryClass?:'LOTTERY_SALE'|'LOTTERY_PAYOUT'}} Department */

const BASE = '/api/ingestion/verifone';

/**
 * Returns all Verifone stores known to verifone_db.
 * @returns {Promise<Store[]>}
 */
export async function listStores() {
  return request(`${BASE}/stores`);
}

/**
 * Copies the VERIFONE stores of auth_db into master.stores.
 * @returns {Promise<SyncResult>}
 */
export async function syncStores() {
  return request(`${BASE}/stores/sync`, {
    method: 'POST',
  });
}

/**
 * Lists days in a given month that have a CPJ zip in S3.
 * @param {number} storeId
 * @param {number} year
 * @param {number} month (1..12)
 * @returns {Promise<string[]>}
 */
export async function getAvailableDays(storeId, year, month) {
  return request(`${BASE}/stores/${storeId}/available-days${query({ year, month })}`);
}

/**
 * Lists business days already loaded for a store within a date range.
 * @param {number} storeId
 * @param {string} from YYYY-MM-DD
 * @param {string} to YYYY-MM-DD
 * @returns {Promise<LoadedDay[]>}
 */
export async function getLoadedDays(storeId, from, to) {
  return request(`${BASE}/stores/${storeId}/loaded-days${query({ from, to })}`);
}

/**
 * Starts a background CPJ fetch for a store and business day.
 * @param {{storeId: number, businessDate: string, mode: 'REFUSE'|'REPLACE'}} params
 * @returns {Promise<{fetchRequestId: number}>}
 */
export async function startFetch({ storeId, businessDate, mode }) {
  return request(`${BASE}/fetches`, {
    method: 'POST',
    body: JSON.stringify({ storeId, businessDate, mode }),
  });
}

/**
 * Gets detail and file progress of a fetch request.
 * @param {number|string} id
 * @returns {Promise<FetchDetail>}
 */
export async function getFetch(id) {
  return request(`${BASE}/fetches/${id}`);
}

/**
 * Lists historical fetch requests with optional filters and paging.
 * @param {{storeId?: number|string, from?: string, to?: string, limit?: number, offset?: number}} [params]
 * @returns {Promise<FetchPage>}
 */
export async function listFetches(params = {}) {
  return request(`${BASE}/fetches${query(params)}`);
}

/**
 * Lists all departments and history of names for a store.
 * @param {number|string} storeId
 * @returns {Promise<Department[]>}
 */
export async function listDepartments(storeId) {
  return request(`${BASE}/stores/${storeId}/departments`);
}

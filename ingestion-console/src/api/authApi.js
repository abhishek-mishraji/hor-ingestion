/**
 * @file authApi.js
 * Auth API client calling hor-auth through Vite proxy (/api/auth).
 */

import { request } from './client.js';

/**
 * @typedef {{
 *   accountType: string,
 *   id: number,
 *   email: string,
 *   fullName: string,
 *   role: string,
 *   allStores: boolean,
 *   stores: Array<{storeId: number, storeCode: string, storeName: string, role?: string}>,
 *   passwordChangedAt?: string,
 *   lastLoginAt?: string
 * }} Me
 */

/**
 * Authenticates user credentials.
 * @param {{email: string, password: string}} credentials
 * @returns {Promise<Me>}
 */
export async function login({ email, password }) {
  return request('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

/**
 * Invalidates the current session.
 * @returns {Promise<null>}
 */
export async function logout() {
  return request('/api/auth/logout', {
    method: 'POST',
  });
}

/**
 * Retrieves the current authenticated user's profile.
 * @returns {Promise<Me>}
 */
export async function getMe() {
  return request('/api/auth/me');
}

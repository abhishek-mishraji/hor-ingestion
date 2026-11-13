import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router';
import * as authApi from '../api/authApi.js';

/**
 * @typedef {import('../api/authApi.js').Me} Me
 */

/**
 * @typedef {object} SessionContextType
 * @property {Me|null} me - Currently authenticated user
 * @property {boolean} ready - Initial check completed
 * @property {() => Promise<void>} refreshMe - Refetch /me
 * @property {(newMe: Me|null) => void} setMe - Set current me object
 * @property {() => Promise<void>} logout - Sign out and navigate to login
 */

const SessionContext = createContext(/** @type {SessionContextType|null} */ (null));

/**
 * SessionProvider manages the authenticated user session via /me.
 * @param {object} props
 * @param {React.ReactNode} props.children
 */
export function SessionProvider({ children }) {
  const [me, setMe] = useState(null);
  const [ready, setReady] = useState(false);
  const navigate = useNavigate();

  const refreshMe = useCallback(async () => {
    try {
      const user = await authApi.getMe();
      setMe(user);
    } catch {
      setMe(null);
    } finally {
      setReady(true);
    }
  }, []);

  useEffect(() => {
    refreshMe();
  }, [refreshMe]);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // Ignore logout errors
    } finally {
      setMe(null);
      navigate('/login', { replace: true });
    }
  }, [navigate]);

  const value = {
    me,
    ready,
    refreshMe,
    setMe,
    logout,
  };

  return (
    <SessionContext.Provider value={value}>
      {children}
    </SessionContext.Provider>
  );
}

/**
 * Hook to consume session state.
 * @returns {SessionContextType}
 */
export function useSession() {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error('useSession must be used within a SessionProvider');
  }
  return context;
}

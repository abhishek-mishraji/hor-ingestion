import { useLocation, Navigate, Outlet } from 'react-router';
import { useSession } from './SessionProvider.jsx';
import { NoAccess } from '../ui/NoAccess.jsx';

/**
 * Route guard component:
 * - If session is not ready: renders loading spinner.
 * - If not authenticated: redirects to /login?return=...
 * - If authenticated but not PLATFORM_ADMIN: renders NoAccess.
 * - Otherwise: renders children or Outlet.
 * @param {object} props
 * @param {React.ReactNode} [props.children]
 */
export function RequireAdmin({ children }) {
  const { me, ready } = useSession();
  const location = useLocation();

  if (!ready) {
    return (
      <div className="loading-container" role="status">
        <div className="spinner" aria-hidden="true" />
        <span>Loading session…</span>
      </div>
    );
  }

  if (!me) {
    const fullPath = location.pathname + location.search;
    const isRoot = location.pathname === '/' || location.pathname === '';
    const loginUrl = isRoot ? '/login' : `/login?return=${encodeURIComponent(fullPath)}`;
    return <Navigate to={loginUrl} replace />;
  }

  if (me.role !== 'PLATFORM_ADMIN') {
    return <NoAccess />;
  }

  return children ?? <Outlet />;
}

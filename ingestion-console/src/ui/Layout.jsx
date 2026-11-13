import { useState, useEffect } from 'react';
import { NavLink, Link, Outlet, useLocation } from 'react-router';
import { useSession } from '../session/SessionProvider.jsx';
import { useVendor } from '../vendors/VendorProvider.jsx';
import { StatusBadge } from './StatusBadge.jsx';

/**
 * Main application layout frame for HOR Ingestion Console.
 * @param {object} props
 * @param {React.ReactNode} [props.children]
 */
export function Layout({ children }) {
  const { me, logout } = useSession();
  const { vendor, config } = useVendor();
  const location = useLocation();
  const authConsoleUrl = import.meta.env.VITE_AUTH_CONSOLE_URL || 'http://localhost:5180/';

  // Theme toggle: 'auto' | 'light' | 'dark'
  const [theme, setTheme] = useState(() => {
    return localStorage.getItem('hor-theme') || 'auto';
  });

  useEffect(() => {
    if (theme === 'auto') {
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.setAttribute('data-theme', theme);
    }
    localStorage.setItem('hor-theme', theme);
  }, [theme]);

  const toggleTheme = () => {
    if (theme === 'auto') {
      const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      setTheme(isDark ? 'light' : 'dark');
    } else if (theme === 'dark') {
      setTheme('light');
    } else {
      setTheme('dark');
    }
  };

  // Determine equivalent tab for vendor switching (strip store/fetch specific queries)
  const segments = location.pathname.replace(/^\//, '').split('/');
  const subSection = segments[1] || 'stores';
  const targetSubpath = subSection === 'fetches' ? 'history' : subSection;

  return (
    <div className="app-container">
      <header className="app-header">
        <div className="header-inner">
          <div className="brand-section">
            <Link
              to={`/${vendor}/stores`}
              style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', textDecoration: 'none' }}
            >
              <div className="brand-logo" aria-hidden="true">H</div>
              <span className="brand-title">HOR Ingestion</span>
            </Link>
            <span className="brand-badge">{config.label}</span>
          </div>

          <div className="vendor-switcher" role="group" aria-label="POS Vendor">
            <Link
              to={`/verifone/${targetSubpath}`}
              className={`vendor-btn ${vendor === 'verifone' ? 'active' : ''}`}
              aria-current={vendor === 'verifone' ? 'page' : undefined}
            >
              Verifone
            </Link>
            <Link
              to={`/gilbarco/${targetSubpath}`}
              className={`vendor-btn ${vendor === 'gilbarco' ? 'active' : ''}`}
              aria-current={vendor === 'gilbarco' ? 'page' : undefined}
            >
              Gilbarco
            </Link>
          </div>

          <nav aria-label="Main Navigation">
            <ul className="nav-links">
              <li>
                <NavLink
                  to={`/${vendor}/stores`}
                  className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
                >
                  Stores
                </NavLink>
              </li>
              <li>
                <NavLink
                  to={`/${vendor}/fetch`}
                  className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
                >
                  Fetch
                </NavLink>
              </li>
              <li>
                <NavLink
                  to={`/${vendor}/history`}
                  className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
                >
                  History
                </NavLink>
              </li>
              <li>
                <NavLink
                  to={`/${vendor}/departments`}
                  className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}
                >
                  Departments
                </NavLink>
              </li>
              <li>
                <a
                  href={authConsoleUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="nav-link"
                  title="Open HOR Auth Console"
                >
                  Auth Console
                  <span className="external-link-icon" aria-hidden="true">↗</span>
                </a>
              </li>
            </ul>
          </nav>

          <div className="header-actions">
            <button
              type="button"
              onClick={toggleTheme}
              className="btn btn-secondary btn-sm"
              title="Toggle color theme"
              aria-label="Toggle color theme"
            >
              {theme === 'dark' ? '☀️' : theme === 'light' ? '🌙' : '🌓'}
            </button>

            {me && (
              <div className="user-profile-badge">
                <span className="user-name">{me.fullName}</span>
                <StatusBadge role={me.role} />
              </div>
            )}

            <button
              type="button"
              onClick={logout}
              className="btn btn-secondary btn-sm"
              aria-label="Log out"
            >
              Log out
            </button>
          </div>
        </div>
      </header>

      <main className="page-container">
        {children || <Outlet />}
      </main>
    </div>
  );
}

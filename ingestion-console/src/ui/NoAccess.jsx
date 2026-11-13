import { useSession } from '../session/SessionProvider.jsx';

/**
 * NoAccess screen displayed when an authenticated user is not a PLATFORM_ADMIN.
 */
export function NoAccess() {
  const { me, logout } = useSession();
  const authConsoleUrl = import.meta.env.VITE_AUTH_CONSOLE_URL || 'http://localhost:5180/';

  return (
    <div className="card access-denied-card">
      <div style={{ fontSize: '3rem', marginBottom: '1rem' }} aria-hidden="true">
        🔒
      </div>
      <h2 style={{ fontSize: '1.25rem', fontWeight: 700, marginBottom: '0.5rem' }}>
        You do not have access
      </h2>
      <p style={{ color: 'var(--text-secondary)', marginBottom: '1.5rem', lineHeight: 1.5 }}>
        The ingestion console is for platform administrators.
        {me ? (
          <>
            <br />
            You are signed in as <strong>{me.fullName}</strong> ({me.role}).
          </>
        ) : null}
      </p>
      <div style={{ display: 'flex', justifyContent: 'center', gap: '0.75rem', flexWrap: 'wrap' }}>
        <button
          type="button"
          onClick={logout}
          className="btn btn-secondary"
        >
          Log out
        </button>
        <a
          href={authConsoleUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="btn btn-primary"
        >
          Auth Console <span aria-hidden="true">↗</span>
        </a>
      </div>
    </div>
  );
}

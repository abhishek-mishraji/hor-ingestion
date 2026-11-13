import { Link } from 'react-router';

/**
 * 404 Not Found page.
 */
export function NotFoundPage() {
  return (
    <div className="card" style={{ textAlign: 'center', padding: '3rem 1.5rem', maxWidth: 500, margin: '2rem auto' }}>
      <div style={{ fontSize: '3rem', marginBottom: '1rem' }} aria-hidden="true">
        🔍
      </div>
      <h1 style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: '0.5rem' }}>
        Page not found
      </h1>
      <p style={{ color: 'var(--text-muted)', marginBottom: '1.5rem' }}>
        The page you requested does not exist or has been moved.
      </p>
      <Link to="/stores" className="btn btn-primary">
        Go to Stores
      </Link>
    </div>
  );
}
export default NotFoundPage;

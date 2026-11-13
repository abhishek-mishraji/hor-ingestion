import { useState, useEffect, useCallback } from 'react';
import { useSearchParams, useNavigate } from 'react-router';
import * as authApi from '../api/authApi.js';
import { useSession } from '../session/SessionProvider.jsx';
import { Field } from '../ui/Field.jsx';
import { ErrorBanner } from '../ui/ErrorBanner.jsx';

/**
 * Handles post-login navigation relative to the basename (/admin).
 * @param {string|null} returnParam
 * @param {(to: string, options?: object) => void} navigate
 */
function navigateAfterLogin(returnParam, navigate) {
  let target = '/stores';
  if (
    returnParam &&
    returnParam.startsWith('/') &&
    !returnParam.startsWith('//') &&
    !returnParam.includes('/login')
  ) {
    target = returnParam;
  }
  navigate(target, { replace: true });
}

/**
 * Login screen for HOR Ingestion Console.
 */
export function LoginPage() {
  const [searchParams] = useSearchParams();
  const rawReturnParam = searchParams.get('return');
  const returnParam =
    rawReturnParam && !rawReturnParam.includes('/login') ? rawReturnParam : null;

  const navigate = useNavigate();
  const { me, ready, setMe } = useSession();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // If already logged in, redirect immediately
  useEffect(() => {
    if (ready && me) {
      navigateAfterLogin(returnParam, navigate);
    }
  }, [ready, me, returnParam, navigate]);

  const handleSubmit = useCallback(
    async (e) => {
      e.preventDefault();
      if (!email || !password) return;

      setLoading(true);
      setError(null);

      try {
        const user = await authApi.login({ email, password });
        setMe(user);
        navigateAfterLogin(returnParam, navigate);
      } catch (err) {
        setError(err);
        setPassword(''); // Clear password on error, keep email
      } finally {
        setLoading(false);
      }
    },
    [email, password, returnParam, navigate, setMe]
  );

  const emailFieldError = error?.fieldErrors?.find((f) => f.field === 'email')?.message;
  const passwordFieldError = error?.fieldErrors?.find((f) => f.field === 'password')?.message;

  return (
    <div className="login-screen">
      <div className="login-card">
        <div className="login-brand">
          <div className="brand-logo" aria-hidden="true">H</div>
          <h1>HOR Ingestion</h1>
          <p>Sign in to the store ingestion console</p>
        </div>

        <ErrorBanner error={error} onDismiss={() => setError(null)} />

        <form onSubmit={handleSubmit} noValidate>
          <Field
            label="Email Address"
            name="email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoComplete="username"
            placeholder="admin@example.com"
            disabled={loading}
            error={emailFieldError}
          />

          <Field
            label="Password"
            name="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete="current-password"
            placeholder="••••••••••••"
            disabled={loading}
            error={passwordFieldError}
          />

          <button
            type="submit"
            className="btn btn-primary"
            disabled={loading || !email.trim() || !password}
            style={{ width: '100%', marginTop: '0.75rem' }}
          >
            {loading ? (
              <>
                <span className="spinner" aria-hidden="true" style={{ width: 16, height: 16 }} />
                <span>Signing in…</span>
              </>
            ) : (
              'Sign In'
            )}
          </button>
        </form>

        <p style={{ marginTop: '1.5rem', fontSize: '0.75rem', color: 'var(--text-muted)', textAlign: 'center' }}>
          Signing in here also signs you in to other HOR consoles in this browser.
        </p>
      </div>
    </div>
  );
}
export default LoginPage;

import { Navigate, useParams, useLocation } from 'react-router';

/**
 * LegacyRedirect forwards legacy un-prefixed Verifone URLs to /verifone/...
 * preserving query strings and route params.
 * @param {object} props
 * @param {string} props.to
 */
export function LegacyRedirect({ to }) {
  const params = useParams();
  const location = useLocation();

  let target = to;
  if (params.id && target.includes(':id')) {
    target = target.replace(':id', encodeURIComponent(params.id));
  }

  const destination = `/verifone/${target}${location.search}`;
  return <Navigate to={destination} replace />;
}

import { Routes, Route, Navigate, Outlet } from 'react-router';
import { SessionProvider } from './session/SessionProvider.jsx';
import { RequireAdmin } from './session/RequireAdmin.jsx';
import { VendorProvider } from './vendors/VendorProvider.jsx';
import { StoresProvider } from './session/StoresProvider.jsx';
import { LegacyRedirect } from './vendors/LegacyRedirect.jsx';
import { getLastVendor } from './vendors/vendors.js';
import { Layout } from './ui/Layout.jsx';
import { LoginPage } from './pages/LoginPage.jsx';
import { StoresPage } from './pages/StoresPage.jsx';
import { FetchPage } from './pages/FetchPage.jsx';
import { FetchDetailPage } from './pages/FetchDetailPage.jsx';
import { HistoryPage } from './pages/HistoryPage.jsx';
import { DepartmentsPage } from './pages/DepartmentsPage.jsx';
import { NotFoundPage } from './pages/NotFoundPage.jsx';

function RootRedirect() {
  const targetVendor = getLastVendor();
  return <Navigate to={`/${targetVendor}/stores`} replace />;
}

export default function App() {
  return (
    <SessionProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <RequireAdmin>
              <Outlet />
            </RequireAdmin>
          }
        >
          <Route index element={<RootRedirect />} />
          <Route
            path="/:vendor"
            element={
              <VendorProvider>
                <StoresProvider>
                  <Layout />
                </StoresProvider>
              </VendorProvider>
            }
          >
            <Route index element={<Navigate to="stores" replace />} />
            <Route path="stores" element={<StoresPage />} />
            <Route path="fetch" element={<FetchPage />} />
            <Route path="fetches/:id" element={<FetchDetailPage />} />
            <Route path="history" element={<HistoryPage />} />
            <Route path="departments" element={<DepartmentsPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>

          {/* Legacy Verifone links */}
          <Route path="/stores" element={<LegacyRedirect to="stores" />} />
          <Route path="/fetch" element={<LegacyRedirect to="fetch" />} />
          <Route path="/fetches/:id" element={<LegacyRedirect to="fetches/:id" />} />
          <Route path="/history" element={<LegacyRedirect to="history" />} />
          <Route path="/departments" element={<LegacyRedirect to="departments" />} />
        </Route>
      </Routes>
    </SessionProvider>
  );
}

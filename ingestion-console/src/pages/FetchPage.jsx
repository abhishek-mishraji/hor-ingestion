import { useState, useEffect, useCallback, useMemo } from 'react';
import { useSearchParams, useNavigate, Link } from 'react-router';
import { useVendor } from '../vendors/VendorProvider.jsx';
import { useStores } from '../session/StoresProvider.jsx';
import { StorePicker } from '../ui/StorePicker.jsx';
import { MonthCalendar } from '../ui/MonthCalendar.jsx';
import { ConfirmReplaceModal } from '../ui/ConfirmReplaceModal.jsx';
import { ErrorBanner } from '../ui/ErrorBanner.jsx';
import { Field } from '../ui/Field.jsx';
import {
  todayIn,
  parseMonth,
  toMonthParam,
  monthRange,
  addMonths,
  monthLabel,
  longDate,
} from '../lib/dates.js';
import { formatDateTime, getFriendlyErrorMessage } from '../ui/format.js';

/**
 * FetchPage: Select store, month, and file type, inspect S3 and loaded status, trigger ingestion.
 */
export function FetchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const { vendor, config, api } = useVendor();
  const { stores, byId, loading: storesLoading } = useStores();

  const storeIdParam = searchParams.get('storeId') || '';
  const monthParam = searchParams.get('month') || '';
  const fileTypeParam = searchParams.get('fileType') || '';

  const activeStores = useMemo(() => stores.filter((s) => s.status === 'ACTIVE'), [stores]);

  // Auto-select store if only 1 active store and none specified
  useEffect(() => {
    if (!storesLoading && !storeIdParam && activeStores.length === 1) {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('storeId', String(activeStores[0].storeId));
          return next;
        },
        { replace: true }
      );
    }
  }, [storesLoading, storeIdParam, activeStores, setSearchParams]);

  // Load supported file types for this vendor
  const [fileTypes, setFileTypes] = useState(/** @type {string[]} */ ([]));
  useEffect(() => {
    let active = true;
    api.listFileTypes()
      .then((types) => {
        if (!active) return;
        setFileTypes(Array.isArray(types) ? types : []);
      })
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [api]);

  // Active file type: Gilbarco uses URL param or default; Verifone defaults to CPJ
  const activeFileType = useMemo(() => {
    if (!config.hasFileTypes) return 'CPJ';
    return fileTypeParam || (fileTypes.length > 0 ? fileTypes[0] : 'PJR');
  }, [config.hasFileTypes, fileTypeParam, fileTypes]);

  // If Gilbarco and fileType is not yet in search params, set it
  useEffect(() => {
    if (config.hasFileTypes && !fileTypeParam && fileTypes.length > 0) {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('fileType', fileTypes[0]);
          return next;
        },
        { replace: true }
      );
    }
  }, [config.hasFileTypes, fileTypeParam, fileTypes, setSearchParams]);

  const currentStore = storeIdParam ? byId.get(Number(storeIdParam)) : null;
  const timezone = currentStore?.timezone || null;
  const today = timezone ? todayIn(timezone) : null;
  const currentMonthInTz = today ? today.slice(0, 7) : null;

  // Determine current active { y, m }
  const parsedMonth = useMemo(() => {
    const fromParam = parseMonth(monthParam);
    if (fromParam) return fromParam;
    if (currentMonthInTz) {
      const fromTz = parseMonth(currentMonthInTz);
      if (fromTz) return fromTz;
    }
    const now = new Date();
    return { y: now.getUTCFullYear(), m: now.getUTCMonth() + 1 };
  }, [monthParam, currentMonthInTz]);

  const year = parsedMonth.y;
  const month = parsedMonth.m;

  // Update month in URL
  const setMonthYear = useCallback(
    (newYm) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          next.set('month', toMonthParam(newYm.y, newYm.m));
          return next;
        },
        { replace: true }
      );
    },
    [setSearchParams]
  );

  const nextMonthYm = addMonths(year, month, 1);
  const isNextDisabled = currentMonthInTz ? toMonthParam(nextMonthYm.y, nextMonthYm.m) > currentMonthInTz : false;

  // Month data state
  const [availableDays, setAvailableDays] = useState(/** @type {string[]} */ ([]));
  const [s3Checked, setS3Checked] = useState(false);
  const [loadedDays, setLoadedDays] = useState(/** @type {import('../api/ingestionApi.js').NormalizedLoadedDay[]} */ ([]));
  const [loadingCalendar, setLoadingCalendar] = useState(false);
  const [loadingS3, setLoadingS3] = useState(false);
  const [calendarError, setCalendarError] = useState(null);
  const [s3Error, setS3Error] = useState(null);

  // Day panel & fetch state
  const [selectedDate, setSelectedDate] = useState(null);
  const [mode, setMode] = useState(/** @type {'REFUSE'|'REPLACE'} */ ('REFUSE'));
  const [confirmModalOpen, setConfirmModalOpen] = useState(false);
  const [startingFetch, setStartingFetch] = useState(false);
  const [actionError, setActionError] = useState(null);

  // Auto-fetch loaded days from DB; S3 is only checked on demand
  useEffect(() => {
    if (!storeIdParam || !currentStore) return;

    let active = true;
    setLoadingCalendar(true);
    setCalendarError(null);

    // Reset S3 check state when store, month, or fileType changes
    setAvailableDays([]);
    setS3Checked(false);
    setS3Error(null);

    const range = monthRange(year, month);

    api.loadedDays(currentStore.storeId, range.from, range.to, activeFileType)
      .then((loadedRes) => {
        if (!active) return;
        setLoadedDays(Array.isArray(loadedRes) ? loadedRes : []);
        setLoadingCalendar(false);
      })
      .catch((err) => {
        if (!active) return;
        setCalendarError(err);
        setLoadedDays([]);
        setLoadingCalendar(false);
      });

    return () => {
      active = false;
    };
  }, [storeIdParam, currentStore, year, month, activeFileType, api]);

  // Manual S3 check handler
  const handleCheckS3 = useCallback(async () => {
    if (!currentStore) return;
    setLoadingS3(true);
    setS3Error(null);
    try {
      const res = await api.availableDays(currentStore.storeId, year, month, activeFileType);
      setAvailableDays(Array.isArray(res) ? res : []);
      setS3Checked(true);
    } catch (err) {
      setS3Error(err);
      setAvailableDays([]);
    } finally {
      setLoadingS3(false);
    }
  }, [currentStore, year, month, activeFileType, api]);

  const availableSet = useMemo(() => new Set(availableDays), [availableDays]);
  const loadedMap = useMemo(() => {
    const map = new Map();
    for (const d of loadedDays) {
      map.set(d.businessDate, d);
    }
    return map;
  }, [loadedDays]);

  const selectedLoaded = selectedDate ? loadedMap.get(selectedDate) : null;
  const isSelectedAvailable = selectedDate ? availableSet.has(selectedDate) : false;

  const handleStoreChange = (newStoreId) => {
    setSelectedDate(null);
    setActionError(null);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (newStoreId) {
          next.set('storeId', newStoreId);
        } else {
          next.delete('storeId');
        }
        return next;
      },
      { replace: true }
    );
  };

  const handleFileTypeChange = (newFileType) => {
    setSelectedDate(null);
    setActionError(null);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (newFileType) {
          next.set('fileType', newFileType);
        } else {
          next.delete('fileType');
        }
        return next;
      },
      { replace: true }
    );
  };

  const handleExecuteFetch = async (fetchMode) => {
    if (!currentStore || !selectedDate) return;

    setStartingFetch(true);
    setActionError(null);

    try {
      const res = await api.startFetch({
        storeId: currentStore.storeId,
        businessDate: selectedDate,
        mode: fetchMode,
        fileType: activeFileType,
      });
      setConfirmModalOpen(false);
      navigate(`/${vendor}/fetches/${res.fetchRequestId}`);
    } catch (err) {
      setActionError(err);
    } finally {
      setStartingFetch(false);
    }
  };

  const handleFetchClick = () => {
    setActionError(null);
    if (mode === 'REPLACE') {
      setConfirmModalOpen(true);
    } else {
      handleExecuteFetch('REFUSE');
    }
  };

  const fileTypeOptions = useMemo(
    () => fileTypes.map((ft) => ({ value: ft, label: ft })),
    [fileTypes]
  );

  return (
    <div>
      <div className="page-header">
        <div className="page-title-group">
          <h1>Fetch</h1>
          <p className="page-description">
            Choose an active {config.label} store and month, inspect available archives in S3, and trigger ingestion.
          </p>
        </div>
      </div>

      <div className="card" style={{ padding: '1rem 1.25rem', marginBottom: '1.5rem' }}>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            alignItems: 'flex-end',
            justifyContent: 'space-between',
            gap: '1rem',
          }}
        >
          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', flex: '1 1 320px', alignItems: 'flex-end' }}>
            <div style={{ minWidth: '260px', flex: '1 1 260px' }}>
              <StorePicker
                label="Store"
                stores={stores}
                value={storeIdParam}
                onChange={handleStoreChange}
                activeOnly
                disabled={storesLoading}
              />
            </div>

            {config.hasFileTypes && fileTypes.length > 1 && (
              <div style={{ minWidth: '140px' }}>
                <Field
                  as="select"
                  label="File Type"
                  name="fileType"
                  value={activeFileType}
                  onChange={(e) => handleFileTypeChange(e.target.value)}
                  options={fileTypeOptions}
                />
              </div>
            )}
          </div>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '0.5rem',
              flexWrap: 'wrap',
            }}
          >
            <button
              type="button"
              onClick={() => setMonthYear(addMonths(year, month, -1))}
              disabled={!currentStore}
              className="btn btn-secondary btn-sm"
              aria-label="Previous month"
            >
              ‹ Prev
            </button>
            <span style={{ fontWeight: 700, minWidth: '150px', textAlign: 'center' }}>
              {monthLabel(year, month)}
            </span>
            <button
              type="button"
              onClick={() => setMonthYear(addMonths(year, month, 1))}
              disabled={!currentStore || isNextDisabled}
              className="btn btn-secondary btn-sm"
              aria-label="Next month"
            >
              Next ›
            </button>
            <button
              type="button"
              onClick={() => {
                if (currentMonthInTz) {
                  const todayYm = parseMonth(currentMonthInTz);
                  if (todayYm) setMonthYear(todayYm);
                }
                if (today) setSelectedDate(today);
              }}
              disabled={!currentStore || !today}
              className="btn btn-secondary btn-sm"
            >
              Today
            </button>
          </div>
        </div>
      </div>

      {!currentStore && (
        <div className="empty-state">
          <div className="empty-state-icon" aria-hidden="true">🏪</div>
          <div className="empty-state-text">
            {storesLoading ? 'Loading stores…' : 'Choose an active store above to view available and loaded days.'}
          </div>
        </div>
      )}

      {currentStore && today && (
        <>
          <ErrorBanner error={calendarError} title="Failed to load loaded days" />
          <ErrorBanner error={s3Error} title="Failed to check S3 archives" />

          <div className="fetch-layout">
            <div style={{ position: 'relative' }}>
              {loadingS3 && (
                <div
                  style={{
                    position: 'absolute',
                    top: '1rem',
                    right: '1.25rem',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '0.375rem',
                    fontSize: '0.75rem',
                    color: 'var(--text-muted)',
                    zIndex: 3,
                  }}
                >
                  <span className="spinner" aria-hidden="true" style={{ width: 14, height: 14 }} />
                  <span>Checking S3…</span>
                </div>
              )}

              {loadingCalendar && !loadingS3 && (
                <div className="loading-container">
                  <span className="spinner" aria-hidden="true" />
                  <span>Loading calendar…</span>
                </div>
              )}

              <MonthCalendar
                year={year}
                month={month}
                available={availableSet}
                loaded={loadedMap}
                today={today}
                selected={selectedDate || undefined}
                s3Checked={s3Checked}
                checkingS3={loadingS3}
                onCheckS3={handleCheckS3}
                onSelect={(d) => {
                  setSelectedDate(d);
                  setActionError(null);
                }}
              />
            </div>

            <div className="card" style={{ position: 'sticky', top: '80px' }}>
              <div className="card-header">
                <h2 className="card-title" style={{ fontSize: '1rem' }}>
                  Day details
                </h2>
                {selectedDate && <span className="mono" style={{ fontSize: '0.8125rem' }}>{selectedDate}</span>}
              </div>

              {!selectedDate ? (
                <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>
                  Click a day in the calendar to inspect its status and run a fetch.
                </p>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                  <div>
                    <h3 style={{ fontSize: '1.125rem', fontWeight: 700, color: 'var(--text-main)', marginBottom: '0.25rem' }}>
                      {longDate(selectedDate)}
                    </h3>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', fontSize: '0.875rem' }}>
                    <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: '0.375rem' }}>
                      <strong style={{ color: 'var(--text-secondary)' }}>S3 Archive: </strong>
                      {s3Checked ? (
                        <span>
                          {isSelectedAvailable
                            ? `${activeFileType} zip available`
                            : `No ${activeFileType} zip in S3 for this day`}
                        </span>
                      ) : (
                        <span>
                          <span style={{ color: 'var(--text-muted)' }}>Not checked</span>
                          <button
                            type="button"
                            onClick={handleCheckS3}
                            disabled={loadingS3}
                            className="btn btn-secondary btn-sm"
                            style={{ padding: '0.15rem 0.5rem', fontSize: '0.75rem', marginLeft: '0.5rem' }}
                          >
                            {loadingS3 ? 'Checking…' : 'Check S3'}
                          </button>
                        </span>
                      )}
                    </div>

                    <div>
                      <strong style={{ color: 'var(--text-secondary)' }}>Loaded: </strong>
                      {selectedLoaded ? (
                        <span>
                          Loaded v{selectedLoaded.versionNo} · {selectedLoaded.files} file{selectedLoaded.files === 1 ? '' : 's'}
                          {Array.isArray(selectedLoaded.daySeqs) && selectedLoaded.daySeqs.length > 0
                            ? ` · day seq ${selectedLoaded.daySeqs.join(', ')}`
                            : ''}
                          {' '}· loaded {formatDateTime(selectedLoaded.loadedAt)}
                          {selectedLoaded.fetchRequestId ? (
                            <>
                              {' '}· from{' '}
                              <Link to={`/${vendor}/fetches/${selectedLoaded.fetchRequestId}`}>
                                fetch #{selectedLoaded.fetchRequestId}
                              </Link>
                            </>
                          ) : null}
                        </span>
                      ) : (
                        <span>Not loaded</span>
                      )}
                    </div>
                  </div>

                  <fieldset
                    style={{
                      border: '1px solid var(--border-subtle)',
                      borderRadius: 'var(--radius-sm)',
                      padding: '0.75rem',
                      display: 'flex',
                      flexDirection: 'column',
                      gap: '0.5rem',
                    }}
                  >
                    <legend style={{ fontSize: '0.75rem', fontWeight: 600, color: 'var(--text-muted)', padding: '0 4px' }}>
                      If this day is already loaded
                    </legend>
                    <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', fontSize: '0.8125rem', cursor: 'pointer' }}>
                      <input
                        type="radio"
                        name="fetchMode"
                        value="REFUSE"
                        checked={mode === 'REFUSE'}
                        onChange={() => setMode('REFUSE')}
                        style={{ marginTop: '2px' }}
                      />
                      <span>
                        <strong>REFUSE (safe)</strong>: Do nothing if the day is already loaded.
                      </span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem', fontSize: '0.8125rem', cursor: 'pointer' }}>
                      <input
                        type="radio"
                        name="fetchMode"
                        value="REPLACE"
                        checked={mode === 'REPLACE'}
                        onChange={() => setMode('REPLACE')}
                        style={{ marginTop: '2px' }}
                      />
                      <span>
                        <strong>REPLACE (destructive)</strong>: Delete the loaded data for this day and load it again.
                      </span>
                    </label>
                  </fieldset>

                  {actionError && (
                    <div className="banner banner-danger" style={{ marginBottom: 0 }}>
                      <div className="banner-content">
                        {actionError.code === 'FETCH_RUNNING' ? (
                          <div>
                            A fetch of this store, day and file type is already running.{' '}
                            <Link to={`/${vendor}/history?storeId=${currentStore.storeId}`}>
                              View store history
                            </Link>
                          </div>
                        ) : actionError.code === 'FETCH_QUEUE_FULL' ? (
                          <div>Too many fetches are waiting; try again in a minute.</div>
                        ) : (
                          <div>{getFriendlyErrorMessage(actionError, config)}</div>
                        )}
                      </div>
                    </div>
                  )}

                  <button
                    type="button"
                    onClick={handleFetchClick}
                    disabled={startingFetch}
                    className="btn btn-primary"
                    style={{ width: '100%', marginTop: '0.5rem' }}
                  >
                    {startingFetch ? (
                      <>
                        <span className="spinner" aria-hidden="true" style={{ width: 16, height: 16 }} />
                        <span>Starting fetch…</span>
                      </>
                    ) : (
                      'Fetch day'
                    )}
                  </button>
                </div>
              )}
            </div>
          </div>

          {selectedDate && (
            <ConfirmReplaceModal
              isOpen={confirmModalOpen}
              date={selectedDate}
              storeCode={currentStore.storeCode}
              onConfirm={() => handleExecuteFetch('REPLACE')}
              onClose={() => setConfirmModalOpen(false)}
              busy={startingFetch}
            />
          )}
        </>
      )}
    </div>
  );
}

export default FetchPage;

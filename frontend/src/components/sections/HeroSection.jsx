import { useState } from 'react';
import ResultCard from '../ResultCard';

export default function HeroSection() {
  const [longUrl, setLongUrl] = useState('');
  const [customCode, setCustomCode] = useState('');
  const [expiryType, setExpiryType] = useState('none');
  const [expiryValue, setExpiryValue] = useState('');
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [resultData, setResultData] = useState(null);

  const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080';

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');

    if (!longUrl.trim()) {
      setError('Please enter a URL');
      return;
    }

    setLoading(true);
    try {
      const payload = { longUrl: longUrl.trim() };
      if (customCode.trim()) {
        payload.customCode = customCode.trim();
      }

      if (expiryType === 'minutes' && expiryValue) {
        payload.expiryTimeMinutes = parseInt(expiryValue);
      } else if (expiryType === 'hours' && expiryValue) {
        payload.expiryTimeHours = parseInt(expiryValue);
      } else if (expiryType === 'days' && expiryValue) {
        payload.expiryTimeDays = parseInt(expiryValue);
      } else if (expiryType === 'timestamp' && expiryValue) {
        payload.expiresAtTimestamp = expiryValue;
      }

      const response = await fetch(`${API_BASE}/api/shorten`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      const data = await response.json();

      if (!response.ok) {
        setError(data.message || 'Failed to shorten URL');
        return;
      }

      setResult(data.shortUrl);
      setResultData(data);
      setLongUrl('');
      setCustomCode('');
      setExpiryType('none');
      setExpiryValue('');
      setShowAdvanced(false);
    } catch (err) {
      setError('Network error. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const handleCreateAnother = () => {
    setResult(null);
    setResultData(null);
    setError('');
  };

  return (
    <section className="hero" id="shorten">
      <p className="section-eyebrow">SHORTEN YOUR LINK</p>
      <h1 className="hero-title">
        Turn long URLs into<br /><span className="hero-accent">tiny links.</span>
      </h1>
      <p className="hero-subtitle">
        Create a clean, shareable link in seconds.
      </p>

      {!result ? (
        <form className="hero-form" onSubmit={handleSubmit}>
          <div className="url-input-row">
            <div className="url-input-wrapper">
              <svg className="url-icon" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" />
                <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" />
              </svg>
              <input
                type="url"
                placeholder="Paste your long URL..."
                value={longUrl}
                onChange={(e) => setLongUrl(e.target.value)}
                disabled={loading}
                className="url-input"
              />
            </div>
            <button type="submit" disabled={loading} className="btn-primary">
              {loading ? (
                <span className="btn-loading">
                  <span className="spinner" />
                  Generating
                </span>
              ) : (
                'Create Link'
              )}
            </button>
          </div>

          <button
            type="button"
            className="advanced-toggle"
            onClick={() => setShowAdvanced(!showAdvanced)}
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={`chevron ${showAdvanced ? 'rotated' : ''}`}>
              <polyline points="6 9 12 15 18 9" />
            </svg>
            Advanced options
          </button>

          {showAdvanced && (
            <div className="advanced-options">
              <div className="advanced-row">
                <div className="advanced-field">
                  <label>Custom Alias</label>
                  <input
                    type="text"
                    placeholder="e.g. my-link"
                    value={customCode}
                    onChange={(e) => setCustomCode(e.target.value)}
                    disabled={loading}
                  />
                </div>
                <div className="advanced-field">
                  <label>Link Expiry</label>
                  <div className="expiry-select-group">
                    <select
                      value={expiryType}
                      onChange={(e) => { setExpiryType(e.target.value); setExpiryValue(''); }}
                      disabled={loading}
                      className="expiry-select"
                    >
                      <option value="none">No Expiry</option>
                      <option value="minutes">Minutes</option>
                      <option value="hours">Hours</option>
                      <option value="days">Days</option>
                      <option value="timestamp">Date/Time</option>
                    </select>
                    {expiryType !== 'none' && (
                      expiryType === 'timestamp' ? (
                        <input
                          type="datetime-local"
                          value={expiryValue}
                          onChange={(e) => setExpiryValue(e.target.value)}
                          disabled={loading}
                          className="expiry-value-input"
                        />
                      ) : (
                        <input
                          type="number"
                          placeholder={`# ${expiryType}`}
                          value={expiryValue}
                          onChange={(e) => setExpiryValue(e.target.value)}
                          disabled={loading}
                          className="expiry-value-input"
                          min="1"
                        />
                      )
                    )}
                  </div>
                </div>
              </div>
            </div>
          )}

          {error && <div className="form-error">{error}</div>}
        </form>
      ) : (
        <ResultCard
          result={result}
          resultData={resultData}
          onCreateAnother={handleCreateAnother}
        />
      )}

      <aside className="link-security-notice" aria-labelledby="link-security-title">
        <svg
          className="link-security-icon"
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.7"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z" />
          <path d="m9 12 2 2 4-4" />
        </svg>
        <div className="link-security-copy">
          <h2 id="link-security-title">Link Security</h2>
          <p>
            To help prevent malicious links, URLs submitted for shortening may be checked using Google Safe Browsing.
            The submitted URL is shared with Google for security screening.
          </p>
          <p className="link-security-attribution">Powered by Google Safe Browsing.</p>
        </div>
      </aside>
    </section>
  );
}

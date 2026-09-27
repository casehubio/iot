import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchCorrelation, clearCorrelationCache, renderCorrelation } from './audit-correlation.js';
import type { AuditRecord } from '../types/bridge-audit.js';

function makeRecord(overrides: Partial<AuditRecord> = {}): AuditRecord {
  return {
    eventType: "STATE_CHANGE",
    deviceId: "light-1",
    correlationId: "corr-1",
    payload: null,
    occurredAt: "2026-09-01T10:00:00Z",
    ...overrides,
  };
}

describe('fetchCorrelation', () => {
  beforeEach(() => {
    clearCorrelationCache();
    vi.restoreAllMocks();
  });

  it('fetches and caches correlated events', async () => {
    const records = [makeRecord(), makeRecord({ deviceId: "light-2" })];
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ records }),
    } as Response);

    const result = await fetchCorrelation("corr-1");
    expect(result).toHaveLength(2);
    expect(result[0].deviceId).toBe("light-1");

    const cached = await fetchCorrelation("corr-1");
    expect(cached).toBe(result);
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });

  it('encodes correlationId in URL', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ records: [] }),
    } as Response);

    await fetchCorrelation("has spaces & special=chars");
    expect(globalThis.fetch).toHaveBeenCalledWith(
      '/api/bridge/audit?correlationId=has%20spaces%20%26%20special%3Dchars'
    );
  });

  it('evicts cache on fetch error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new Error('network'));

    await expect(fetchCorrelation("corr-err")).rejects.toThrow('network');

    const records = [makeRecord()];
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ records }),
    } as Response);

    const result = await fetchCorrelation("corr-err");
    expect(result).toHaveLength(1);
  });

  it('evicts cache on non-ok response', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: false,
      status: 500,
    } as Response);

    await expect(fetchCorrelation("corr-500")).rejects.toThrow('HTTP 500');

    const records = [makeRecord()];
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ records }),
    } as Response);

    const result = await fetchCorrelation("corr-500");
    expect(result).toHaveLength(1);
  });
});

describe('renderCorrelation', () => {
  beforeEach(() => {
    clearCorrelationCache();
    vi.restoreAllMocks();
  });

  it('returns a TemplateResult with until() directive', () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ records: [makeRecord(), makeRecord({ occurredAt: "2026-09-01T10:01:00Z" })] }),
    } as Response);

    const result = renderCorrelation("corr-render", "2026-09-01T10:00:00Z");
    expect(result).toBeDefined();
    const r = result as unknown as { strings: readonly string[]; values: unknown[] };
    expect(r.values).toHaveLength(1);
  });

  it('renders empty when only one event in correlation', async () => {
    const records = [makeRecord()];
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce({
      ok: true,
      json: () => Promise.resolve({ records }),
    } as Response);

    const result = renderCorrelation("corr-single");
    expect(result).toBeDefined();

    const resolved = await fetchCorrelation("corr-single");
    expect(resolved).toHaveLength(1);
  });
});

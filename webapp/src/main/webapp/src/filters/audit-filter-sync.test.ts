import { describe, it, expect, vi, beforeEach } from 'vitest';
import './audit-filter-sync.js';

describe('audit-filter-sync', () => {
  let trailEl: { endpoint: string; syncEndpoint: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    trailEl = { endpoint: '/api/bridge/audit?limit=500', syncEndpoint: vi.fn() };
    vi.spyOn(document, 'querySelector').mockImplementation((sel: string) =>
      sel === 'blocks-event-trail' ? trailEl as any : null
    );
  });

  function fireFilter(detail: Record<string, unknown>) {
    document.dispatchEvent(new CustomEvent('pages-filter', { detail }));
  }

  it('updates trail endpoint on filter apply', () => {
    fireFilter({ columnId: 'eventType', value: 'STATE_CHANGE', reset: false, group: 'audit' });
    expect(trailEl.endpoint).toContain('eventType=STATE_CHANGE');
    expect(trailEl.syncEndpoint).toHaveBeenCalled();
  });

  it('removes filter param on reset', () => {
    fireFilter({ columnId: 'eventType', value: 'STATE_CHANGE', reset: false, group: 'audit' });
    fireFilter({ columnId: 'eventType', value: '', reset: true, group: 'audit' });
    expect(trailEl.endpoint).not.toContain('eventType');
    expect(trailEl.syncEndpoint).toHaveBeenCalledTimes(2);
  });

  it('combines multiple filters', () => {
    fireFilter({ columnId: 'eventType', value: 'COMMAND_SENT', reset: false, group: 'audit' });
    fireFilter({ columnId: 'deviceId', value: 'light-1', reset: false, group: 'audit' });
    expect(trailEl.endpoint).toContain('eventType=COMMAND_SENT');
    expect(trailEl.endpoint).toContain('deviceId=light-1');
  });

  it('ignores non-audit filter groups', () => {
    fireFilter({ columnId: 'eventType', value: 'X', reset: false, group: 'other' });
    expect(trailEl.syncEndpoint).not.toHaveBeenCalled();
  });

  it('handles missing trail element gracefully', () => {
    vi.spyOn(document, 'querySelector').mockReturnValue(null);
    expect(() => fireFilter({ columnId: 'eventType', value: 'X', reset: false, group: 'audit' })).not.toThrow();
  });
});

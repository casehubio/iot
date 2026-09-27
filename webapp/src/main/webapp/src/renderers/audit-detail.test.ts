import { describe, it, expect } from 'vitest';
import { renderPayload, auditRowKey, renderAuditDetail } from './audit-detail.js';
import { nothing } from 'lit';
import type {
  StateChangePayload, ReplayedStateChangePayload,
  StateSnapshotPayload, ProviderStatusPayload, CommandPayload,
  CommandResponsePayload, AuditRecord, DeviceEntity,
} from '../types/bridge-audit.js';
import type { TypedRow } from '@casehubio/pages-data';
import type { ColumnId } from '@casehubio/pages-data';

function makeDevice(overrides: Partial<DeviceEntity> = {}): DeviceEntity {
  return {
    "@deviceType": "light",
    deviceId: "light-1",
    deviceClass: "dimmable-light",
    label: "Kitchen Light",
    available: true,
    lastUpdated: "2026-09-01T10:00:00Z",
    tenancyId: "tenant-1",
    providerId: "ha",
    location: null,
    ...overrides,
  };
}

function collectText(result: unknown): string {
  if (result === nothing || result == null) return '';
  if (typeof result === 'string' || typeof result === 'number' || typeof result === 'boolean') {
    return String(result);
  }
  if (Array.isArray(result)) return result.map(collectText).join('');
  const r = result as { strings?: readonly string[]; values?: unknown[] };
  if (!r.strings) return '';
  let text = '';
  for (let i = 0; i < r.strings.length; i++) {
    text += r.strings[i];
    if (r.values && i < r.values.length) {
      text += collectText(r.values[i]);
    }
  }
  return text;
}

describe('renderPayload', () => {
  it('renders AGENT_CONNECTED lifecycle event', () => {
    const text = collectText(renderPayload("AGENT_CONNECTED", null));
    expect(text).toContain('lifecycle-event');
    expect(text).toContain('badge-connected');
    expect(text).toContain('Bridge agent connected');
  });

  it('renders AGENT_DISCONNECTED lifecycle event', () => {
    const text = collectText(renderPayload("AGENT_DISCONNECTED", null));
    expect(text).toContain('badge-disconnected');
    expect(text).toContain('Bridge agent disconnected');
  });

  it('renders STATE_CHANGE with capability diff', () => {
    const payload: StateChangePayload = {
      "@type": "STATE_CHANGE",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      event: {
        before: makeDevice({ brightness: 50 }),
        after: makeDevice({ brightness: 100 }),
        changedCapabilities: ["brightness"],
        occurredAt: "2026-09-01T10:00:00Z",
        providerId: "ha",
      },
    };
    const text = collectText(renderPayload("STATE_CHANGE", payload));
    expect(text).toContain('state-change-detail');
    expect(text).toContain('capability-diff');
    expect(text).toContain('brightness');
    expect(text).toContain('Kitchen Light');
  });

  it('renders STATE_CHANGE with no changed capabilities', () => {
    const payload: StateChangePayload = {
      "@type": "STATE_CHANGE",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      event: {
        before: null,
        after: makeDevice(),
        changedCapabilities: [],
        occurredAt: "2026-09-01T10:00:00Z",
        providerId: "ha",
      },
    };
    const text = collectText(renderPayload("STATE_CHANGE", payload));
    expect(text).toContain('state-change-detail');
    expect(text).toContain('No capability changes recorded');
  });

  it('renders REPLAYED_STATE_CHANGE with replay badge', () => {
    const payload: ReplayedStateChangePayload = {
      "@type": "REPLAYED_STATE_CHANGE",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      event: {
        before: makeDevice(),
        after: makeDevice(),
        changedCapabilities: [],
        occurredAt: "2026-09-01T10:00:00Z",
        providerId: "ha",
      },
    };
    const text = collectText(renderPayload("REPLAYED_STATE_CHANGE", payload));
    expect(text).toContain('badge-replayed');
    expect(text).toContain('Replayed');
  });

  it('renders STATE_SNAPSHOT with device count', () => {
    const payload: StateSnapshotPayload = {
      "@type": "STATE_SNAPSHOT",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      devices: [makeDevice(), makeDevice({ deviceId: "light-2", label: "Living Room" })],
    };
    const text = collectText(renderPayload("STATE_SNAPSHOT", payload));
    expect(text).toContain('state-snapshot-detail');
    expect(text).toContain('snapshot-devices');
    expect(text).toContain('2');
    expect(text).toContain('Kitchen Light');
    expect(text).toContain('Living Room');
  });

  it('renders PROVIDER_STATUS with status transition', () => {
    const payload: ProviderStatusPayload = {
      "@type": "PROVIDER_STATUS",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      status: {
        providerId: "openhab-main",
        previousStatus: "CONNECTING",
        currentStatus: "CONNECTED",
      },
    };
    const text = collectText(renderPayload("PROVIDER_STATUS_CHANGE", payload));
    expect(text).toContain('provider-status-detail');
    expect(text).toContain('openhab-main');
    expect(text).toContain('CONNECTING');
    expect(text).toContain('CONNECTED');
  });

  it('renders COMMAND with parameters', () => {
    const payload: CommandPayload = {
      "@type": "COMMAND",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      correlationId: "corr-1",
      command: {
        targetDeviceId: "light-1",
        action: "turn_on",
        parameters: { brightness: 100 },
        dispatchedBy: "user@example.com",
        correlationId: "corr-1",
      },
    };
    const text = collectText(renderPayload("COMMAND_SENT", payload));
    expect(text).toContain('command-detail');
    expect(text).toContain('command-params');
    expect(text).toContain('light-1');
    expect(text).toContain('turn_on');
    expect(text).toContain('brightness');
  });

  it('renders COMMAND without parameters omits table', () => {
    const payload: CommandPayload = {
      "@type": "COMMAND",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      correlationId: "corr-1",
      command: {
        targetDeviceId: "light-1",
        action: "turn_off",
        parameters: {},
        dispatchedBy: "user@example.com",
        correlationId: "corr-1",
      },
    };
    const text = collectText(renderPayload("COMMAND_SENT", payload));
    expect(text).toContain('command-detail');
    expect(text).not.toContain('command-params');
  });

  it('renders COMMAND_RESULT SENT with success badge', () => {
    const payload: CommandResponsePayload = {
      "@type": "COMMAND_RESULT",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      correlationId: "corr-1",
      result: "SENT",
    };
    const text = collectText(renderPayload("COMMAND_RESPONSE", payload));
    expect(text).toContain('command-response-detail');
    expect(text).toContain('badge-success');
  });

  it('renders COMMAND_RESULT FAILED with error badge', () => {
    const payload: CommandResponsePayload = {
      "@type": "COMMAND_RESULT",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      correlationId: "corr-1",
      result: "FAILED",
    };
    const text = collectText(renderPayload("COMMAND_RESPONSE", payload));
    expect(text).toContain('badge-error');
  });

  it('renders COMMAND_RESULT TIMEOUT with warning badge', () => {
    const payload: CommandResponsePayload = {
      "@type": "COMMAND_RESULT",
      tenancyId: "t1",
      timestamp: "2026-09-01T10:00:00Z",
      correlationId: "corr-1",
      result: "TIMEOUT",
    };
    const text = collectText(renderPayload("COMMAND_RESPONSE", payload));
    expect(text).toContain('badge-warning');
  });
});

describe('auditRowKey', () => {
  function mockRow(values: Record<string, { type: string; value?: unknown }>): TypedRow {
    return {
      cells: [],
      cell(columnId: ColumnId) {
        return values[columnId as string] as any;
      },
      number(columnId: ColumnId) {
        return (values[columnId as string]?.value ?? 0) as number;
      },
      text(columnId: ColumnId) {
        return (values[columnId as string]?.value ?? '') as string;
      },
      date(columnId: ColumnId) {
        return new Date(values[columnId as string]?.value as string);
      },
    };
  }

  it('builds key from occurredAt, eventType, and deviceId', () => {
    const row = mockRow({
      occurredAt: { type: "DATE", value: "2026-09-01T10:00:00Z" },
      eventType: { type: "TEXT", value: "STATE_CHANGE" },
      deviceId: { type: "TEXT", value: "light-1" },
    });
    const key = auditRowKey(row);
    expect(key).toBe("2026-09-01T10:00:00.000Z-STATE_CHANGE-light-1");
  });

  it('uses "system" when deviceId is NULL', () => {
    const row = mockRow({
      occurredAt: { type: "DATE", value: "2026-09-01T10:00:00Z" },
      eventType: { type: "TEXT", value: "AGENT_CONNECTED" },
      deviceId: { type: "NULL" },
    });
    const key = auditRowKey(row);
    expect(key).toContain("-system");
  });
});

describe('renderAuditDetail', () => {
  function mockRow(): TypedRow {
    return { cells: [], cell: () => ({} as any), number: () => 0, text: () => '', date: () => new Date() };
  }

  it('returns undefined when rawEntry is missing', () => {
    const result = renderAuditDetail(mockRow());
    expect(result).toBeUndefined();
  });

  it('renders payload and correlation for a full record', () => {
    const record: AuditRecord = {
      eventType: "AGENT_CONNECTED",
      deviceId: null,
      correlationId: "corr-1",
      payload: null,
      occurredAt: "2026-09-01T10:00:00Z",
    };
    const text = collectText(renderAuditDetail(mockRow(), record));
    expect(text).toContain('audit-detail');
    expect(text).toContain('lifecycle-event');
  });

  it('skips correlation when correlationId is null', () => {
    const record: AuditRecord = {
      eventType: "AGENT_CONNECTED",
      deviceId: null,
      correlationId: null,
      payload: null,
      occurredAt: "2026-09-01T10:00:00Z",
    };
    const result = renderAuditDetail(mockRow(), record);
    expect(result).toBeDefined();
    const r = result as unknown as { values: unknown[] };
    expect(r.values[1]).toBe(nothing);
  });
});

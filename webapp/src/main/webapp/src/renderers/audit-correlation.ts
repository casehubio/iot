import { html, type TemplateResult } from 'lit';
import { until } from 'lit/directives/until.js';
import type { AuditRecord, BridgeAuditEventType } from '../types/bridge-audit.js';

const correlationCache = new Map<string, Promise<AuditRecord[]>>();

export function clearCorrelationCache(): void {
  correlationCache.clear();
}

export function fetchCorrelation(correlationId: string): Promise<AuditRecord[]> {
  if (!correlationCache.has(correlationId)) {
    correlationCache.set(correlationId,
      fetch(`/api/bridge/audit?correlationId=${encodeURIComponent(correlationId)}`)
        .then(r => {
          if (!r.ok) throw new Error(`HTTP ${r.status}`);
          return r.json();
        })
        .then(body => body.records as AuditRecord[])
        .catch(err => {
          correlationCache.delete(correlationId);
          throw err;
        })
    );
  }
  return correlationCache.get(correlationId)!;
}

const EVENT_TYPE_LABELS: Record<BridgeAuditEventType, string> = {
  STATE_CHANGE: "State Change",
  REPLAYED_STATE_CHANGE: "Replayed",
  STATE_SNAPSHOT: "Snapshot",
  PROVIDER_STATUS_CHANGE: "Provider",
  COMMAND_SENT: "Command",
  COMMAND_RESPONSE: "Response",
  AGENT_CONNECTED: "Connected",
  AGENT_DISCONNECTED: "Disconnected",
};

function renderRelatedRow(record: AuditRecord, isCurrent: boolean): TemplateResult {
  return html`
    <tr class="${isCurrent ? 'current-event' : ''}">
      <td>${new Date(record.occurredAt).toLocaleTimeString()}</td>
      <td><span class="badge badge-${record.eventType.toLowerCase()}">${EVENT_TYPE_LABELS[record.eventType]}</span></td>
      <td>${record.deviceId ?? '—'}</td>
    </tr>
  `;
}

export function renderCorrelation(correlationId: string, currentOccurredAt?: string): TemplateResult {
  const promise = fetchCorrelation(correlationId)
    .then(events => {
      if (events.length <= 1) return html``;
      return html`
        <div class="related-events">
          <h4>Related Events</h4>
          <table class="related-events-table">
            <thead><tr><th>Time</th><th>Event</th><th>Device</th></tr></thead>
            <tbody>
              ${events.map(e => renderRelatedRow(e, e.occurredAt === currentOccurredAt))}
            </tbody>
          </table>
        </div>
      `;
    })
    .catch(() => html`<span class="error">Failed to load related events</span>`);

  return html`${until(promise, html`<span class="loading">Loading related events...</span>`)}`;
}

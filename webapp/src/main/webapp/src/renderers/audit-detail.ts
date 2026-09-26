import { html, nothing, type TemplateResult } from 'lit';
import type {
  AuditRecord, BridgeAuditEventType, BridgeMessagePayload,
  StateChangePayload, ReplayedStateChangePayload, StateSnapshotPayload,
  ProviderStatusPayload, CommandPayload, CommandResponsePayload,
  StateChangeEvent,
} from '../types/bridge-audit.js';
import type { TypedRow } from '@casehubio/pages-data';
import type { ColumnId } from '@casehubio/pages-data';
import { renderCorrelation } from './audit-correlation.js';

export function renderPayload(
  eventType: BridgeAuditEventType,
  payload: BridgeMessagePayload | null,
): TemplateResult {
  if (payload === null) {
    return renderLifecycleEvent(eventType);
  }

  switch (payload["@type"]) {
    case "STATE_CHANGE":
      return renderStateChange(payload.event, false);
    case "REPLAYED_STATE_CHANGE":
      return renderStateChange(payload.event, true);
    case "STATE_SNAPSHOT":
      return renderStateSnapshot(payload);
    case "PROVIDER_STATUS":
      return renderProviderStatus(payload);
    case "COMMAND":
      return renderCommand(payload);
    case "COMMAND_RESULT":
      return renderCommandResponse(payload);
    default: {
      const _exhaustive: never = payload;
      return html`<span>Unknown payload type</span>`;
    }
  }
}

function renderLifecycleEvent(eventType: BridgeAuditEventType): TemplateResult {
  const label = eventType === "AGENT_CONNECTED"
    ? "Bridge agent connected"
    : "Bridge agent disconnected";
  const badge = eventType === "AGENT_CONNECTED" ? "connected" : "disconnected";
  return html`
    <div class="lifecycle-event">
      <span class="badge badge-${badge}">${label}</span>
    </div>
  `;
}

function renderStateChange(event: StateChangeEvent, replayed: boolean): TemplateResult {
  const device = event.after;
  return html`
    <div class="state-change-detail">
      ${replayed ? html`<span class="badge badge-replayed">Replayed</span>` : nothing}
      <div class="device-header">
        <strong>${device.label}</strong> <code>${device.deviceId}</code>
        <span class="device-type">${device["@deviceType"]}</span>
      </div>
      ${event.changedCapabilities.length > 0 ? html`
        <table class="capability-diff">
          <thead><tr><th>Capability</th><th>Before</th><th>After</th></tr></thead>
          <tbody>
            ${event.changedCapabilities.map(cap => html`
              <tr>
                <td>${cap}</td>
                <td>${event.before ? String(event.before[cap] ?? '—') : '—'}</td>
                <td>${String(device[cap] ?? '—')}</td>
              </tr>
            `)}
          </tbody>
        </table>
      ` : html`<p>No capability changes recorded</p>`}
    </div>
  `;
}

function renderStateSnapshot(payload: StateSnapshotPayload): TemplateResult {
  return html`
    <div class="state-snapshot-detail">
      <p><strong>${payload.devices.length}</strong> device(s) in snapshot</p>
      <table class="snapshot-devices">
        <thead><tr><th>Type</th><th>Device ID</th><th>Label</th><th>Available</th></tr></thead>
        <tbody>
          ${payload.devices.map(d => html`
            <tr>
              <td>${d["@deviceType"]}</td>
              <td><code>${d.deviceId}</code></td>
              <td>${d.label}</td>
              <td>${d.available ? '✓' : '✗'}</td>
            </tr>
          `)}
        </tbody>
      </table>
    </div>
  `;
}

function renderProviderStatus(payload: ProviderStatusPayload): TemplateResult {
  return html`
    <div class="provider-status-detail">
      <p>Provider <strong>${payload.status.providerId}</strong></p>
      <p>
        <span class="badge badge-${payload.status.previousStatus.toLowerCase()}">${payload.status.previousStatus}</span>
        → <span class="badge badge-${payload.status.currentStatus.toLowerCase()}">${payload.status.currentStatus}</span>
      </p>
    </div>
  `;
}

function renderCommand(payload: CommandPayload): TemplateResult {
  const cmd = payload.command;
  return html`
    <div class="command-detail">
      <p>Target: <code>${cmd.targetDeviceId}</code> — Action: <strong>${cmd.action}</strong></p>
      <p>Dispatched by: ${cmd.dispatchedBy}</p>
      ${Object.keys(cmd.parameters).length > 0 ? html`
        <table class="command-params">
          <thead><tr><th>Parameter</th><th>Value</th></tr></thead>
          <tbody>
            ${Object.entries(cmd.parameters).map(([k, v]) => html`
              <tr><td>${k}</td><td>${JSON.stringify(v)}</td></tr>
            `)}
          </tbody>
        </table>
      ` : nothing}
    </div>
  `;
}

function renderCommandResponse(payload: CommandResponsePayload): TemplateResult {
  const badgeClass = payload.result === "SENT" ? "success"
    : payload.result === "FAILED" ? "error" : "warning";
  return html`
    <div class="command-response-detail">
      <span class="badge badge-${badgeClass}">${payload.result}</span>
    </div>
  `;
}

export function auditRowKey(row: TypedRow): string {
  const at = row.date("occurredAt" as ColumnId).toISOString();
  const type = row.text("eventType" as ColumnId);
  const devCell = row.cell("deviceId" as ColumnId);
  const dev = devCell.type === "NULL" ? "system" : (devCell as { value: string }).value;
  return `${at}-${type}-${dev}`;
}

export function renderAuditDetail(row: TypedRow, rawEntry?: unknown): TemplateResult | undefined {
  const record = rawEntry as AuditRecord | undefined;
  if (!record) return undefined;

  return html`
    <div class="audit-detail">
      ${renderPayload(record.eventType, record.payload)}
      ${record.correlationId
        ? renderCorrelation(record.correlationId, record.occurredAt)
        : nothing}
    </div>
  `;
}

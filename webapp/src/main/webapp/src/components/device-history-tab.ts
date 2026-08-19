import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { field } from './tab-utils';

@customElement('iot-device-history-tab')
export class DeviceHistoryTab extends LitElement {
  @property({ type: Object }) item: any = null;
  @state() private _history: any[] = [];
  @state() private _loading = false;

  static override styles = css`
    :host { display: block; padding: var(--pages-space-4, 16px); }
    table { width: 100%; border-collapse: collapse; font-size: var(--pages-font-size-sm, 13px); }
    th { text-align: left; padding: var(--pages-space-2, 8px); border-bottom: 2px solid var(--pages-neutral-5, #e5e5e5); color: var(--pages-neutral-9, #888); font-weight: 600; }
    td { padding: var(--pages-space-2, 8px); border-bottom: 1px solid var(--pages-neutral-3, #f0f0f0); }
    .empty { color: var(--pages-neutral-9, #888); text-align: center; padding: var(--pages-space-6, 24px); }
    .loading { color: var(--pages-neutral-9, #888); text-align: center; padding: var(--pages-space-6, 24px); }
  `;

  override updated(changed: Map<PropertyKey, unknown>) {
    if (changed.has('item') && this.item) this._fetchHistory();
  }

  private async _fetchHistory() {
    const deviceId = field(this.item, 'deviceId');
    if (!deviceId) return;
    this._loading = true;
    try {
      const resp = await fetch(`/api/devices/${deviceId}/history?limit=20`);
      if (resp.ok) this._history = await resp.json();
    } catch { /* ignore */ }
    this._loading = false;
  }

  override render() {
    if (!this.item) return html`<p class="empty">No device selected</p>`;
    if (this._loading) return html`<p class="loading">Loading history...</p>`;
    if (this._history.length === 0) return html`<p class="empty">No state changes recorded</p>`;
    return html`
      <table>
        <thead><tr><th>Time</th><th>Device Class</th><th>Changed</th></tr></thead>
        <tbody>
          ${this._history.map(h => html`
            <tr>
              <td>${new Date(h.occurredAt).toLocaleString()}</td>
              <td>${h.deviceClass}</td>
              <td>${(h.changedCapabilities ?? []).join(', ')}</td>
            </tr>
          `)}
        </tbody>
      </table>
    `;
  }
}

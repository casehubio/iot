import { LitElement, html, css } from 'lit';
import { customElement, property } from 'lit/decorators.js';
import { field } from './tab-utils';

@customElement('iot-device-state-tab')
export class DeviceStateTab extends LitElement {
  @property({ type: Object }) item: any = null;

  static override styles = css`
    :host { display: block; padding: var(--pages-space-4, 16px); }
    .field { display: flex; gap: var(--pages-space-2, 8px); padding: var(--pages-space-1, 4px) 0; font-size: var(--pages-font-size-sm, 13px); }
    .label { color: var(--pages-neutral-9, #888); min-width: 120px; font-weight: 500; }
    .value { color: var(--pages-neutral-12, #111); }
    .status { padding: 2px var(--pages-space-2, 8px); border-radius: var(--pages-radius-sm, 4px); font-size: var(--pages-font-size-xs, 11px); font-weight: 600; text-transform: uppercase; }
    .status.online { background: var(--pages-success-2, #e6f7ed); color: var(--pages-success-11, #0d5a2e); }
    .status.offline { background: var(--pages-danger-2, #fee); color: var(--pages-danger-11, #991b1b); }
  `;

  override render() {
    if (!this.item) return html`<p>No device selected</p>`;
    const f = (name: string, fallback = '') => field(this.item, name, fallback);
    const available = this.item.available ?? this.item.text?.('available') ?? '';
    const isOnline = available === true || available === 'true';
    return html`
      <div class="field">
        <span class="label">Status</span>
        <span class="status ${isOnline ? 'online' : 'offline'}">${isOnline ? 'Online' : 'Offline'}</span>
      </div>
      <div class="field"><span class="label">Device ID</span><span class="value">${f('deviceId')}</span></div>
      <div class="field"><span class="label">Provider</span><span class="value">${f('providerId')}</span></div>
      <div class="field"><span class="label">Device Class</span><span class="value">${f('deviceClass')}</span></div>
      <div class="field"><span class="label">Location</span><span class="value">${f('location', '—')}</span></div>
      <div class="field"><span class="label">Last Updated</span><span class="value">${f('lastUpdated')}</span></div>
    `;
  }
}

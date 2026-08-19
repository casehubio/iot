import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { field } from './tab-utils';

@customElement('iot-device-actions-tab')
export class DeviceActionsTab extends LitElement {
  @property({ type: Object }) item: any = null;
  @state() private _result: string | null = null;

  static override styles = css`
    :host { display: block; padding: var(--pages-space-4, 16px); }
    .actions { display: flex; gap: var(--pages-space-2, 8px); flex-wrap: wrap; }
    button { padding: var(--pages-space-2, 8px) var(--pages-space-4, 16px); border: 1px solid var(--pages-neutral-5, #d4d4d4); border-radius: var(--pages-radius-sm, 4px); background: var(--pages-neutral-2, #fafafa); cursor: pointer; font-size: var(--pages-font-size-sm, 13px); color: var(--pages-neutral-12, #111); }
    button:hover { background: var(--pages-neutral-3, #e5e5e5); }
    .result { margin-top: var(--pages-space-3, 12px); padding: var(--pages-space-2, 8px); border-radius: var(--pages-radius-sm, 4px); font-size: var(--pages-font-size-sm, 13px); }
    .result.sent { background: var(--pages-success-2, #e6f7ed); color: var(--pages-success-11, #0d5a2e); }
    .result.failed { background: var(--pages-danger-2, #fee); color: var(--pages-danger-11, #991b1b); }
  `;

  private async _dispatch(action: string) {
    const deviceId = field(this.item, 'deviceId');
    if (!deviceId) return;
    this._result = null;
    try {
      const resp = await fetch(`/api/devices/${deviceId}/commands`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action, parameters: {} }),
      });
      const data = await resp.json();
      this._result = data.result === 'SENT' ? 'sent' : 'failed';
    } catch {
      this._result = 'failed';
    }
  }

  override render() {
    if (!this.item) return html`<p>No device selected</p>`;
    return html`
      <div class="actions">
        <button @click=${() => this._dispatch('turn_on')}>Turn On</button>
        <button @click=${() => this._dispatch('turn_off')}>Turn Off</button>
        <button @click=${() => this._dispatch('lock')}>Lock</button>
        <button @click=${() => this._dispatch('unlock')}>Unlock</button>
      </div>
      ${this._result ? html`<div class="result ${this._result}">${this._result === 'sent' ? 'Command sent' : 'Command failed'}</div>` : ''}
    `;
  }
}

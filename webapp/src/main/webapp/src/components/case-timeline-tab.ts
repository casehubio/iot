import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { field } from './tab-utils';

@customElement('iot-case-timeline-tab')
export class CaseTimelineTab extends LitElement {
  @property({ type: Object }) item: any = null;
  @state() private _events: any[] = [];
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
    if (changed.has('item') && this.item) this._fetch();
  }

  private async _fetch() {
    const caseId = field(this.item, 'caseId');
    if (!caseId) return;
    this._loading = true;
    try {
      const resp = await fetch(`/api/cases/${caseId}/events`);
      if (resp.ok) this._events = await resp.json();
    } catch { /* ignore */ }
    this._loading = false;
  }

  override render() {
    if (!this.item) return html`<p class="empty">No case selected</p>`;
    if (this._loading) return html`<p class="loading">Loading timeline...</p>`;
    if (this._events.length === 0) return html`<p class="empty">No events</p>`;
    return html`
      <table>
        <thead><tr><th>Time</th><th>Type</th><th>Detail</th></tr></thead>
        <tbody>
          ${this._events.map(e => html`
            <tr>
              <td>${new Date(e.timestamp).toLocaleString()}</td>
              <td>${e.type ?? e.eventType ?? ''}</td>
              <td>${e.detail ?? e.message ?? ''}</td>
            </tr>
          `)}
        </tbody>
      </table>
    `;
  }
}

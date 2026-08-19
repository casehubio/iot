import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { field } from './tab-utils';

@customElement('iot-case-suggestions-tab')
export class CaseSuggestionsTab extends LitElement {
  @property({ type: Object }) item: any = null;
  @state() private _suggestions: any[] = [];
  @state() private _loading = false;

  static override styles = css`
    :host { display: block; padding: var(--pages-space-4, 16px); }
    .suggestion { padding: var(--pages-space-3, 12px); margin-bottom: var(--pages-space-2, 8px); background: var(--pages-neutral-2, #fafafa); border-radius: var(--pages-radius-sm, 4px); border-left: 3px solid var(--pages-accent-9, #3b82f6); }
    .suggestion-title { font-weight: 600; font-size: var(--pages-font-size-sm, 13px); margin-bottom: var(--pages-space-1, 4px); }
    .suggestion-detail { font-size: var(--pages-font-size-xs, 12px); color: var(--pages-neutral-10, #666); }
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
      const resp = await fetch(`/api/cases/${caseId}/suggestions`);
      if (resp.ok) this._suggestions = await resp.json();
    } catch { /* ignore */ }
    this._loading = false;
  }

  override render() {
    if (!this.item) return html`<p class="empty">No case selected</p>`;
    if (this._loading) return html`<p class="loading">Loading suggestions...</p>`;
    if (this._suggestions.length === 0) return html`<p class="empty">No similar past resolutions found</p>`;
    return html`
      ${this._suggestions.map(s => html`
        <div class="suggestion">
          <div class="suggestion-title">${s.title ?? s.description ?? 'Resolution'}</div>
          <div class="suggestion-detail">${s.similarity ? `${(s.similarity * 100).toFixed(0)}% match` : ''}</div>
        </div>
      `)}
    `;
  }
}

import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { field } from './tab-utils';

@customElement('iot-case-actions-tab')
export class CaseActionsTab extends LitElement {
  @property({ type: Object }) item: any = null;
  @state() private _result: string | null = null;

  static override styles = css`
    :host { display: block; padding: var(--pages-space-4, 16px); }
    .actions { display: flex; gap: var(--pages-space-2, 8px); flex-wrap: wrap; }
    button { padding: var(--pages-space-2, 8px) var(--pages-space-4, 16px); border: 1px solid var(--pages-neutral-5, #d4d4d4); border-radius: var(--pages-radius-sm, 4px); background: var(--pages-neutral-2, #fafafa); cursor: pointer; font-size: var(--pages-font-size-sm, 13px); color: var(--pages-neutral-12, #111); }
    button:hover { background: var(--pages-neutral-3, #e5e5e5); }
    .btn-approve { border-color: var(--pages-success-9, #16a34a); color: var(--pages-success-9, #16a34a); }
    .btn-reject { border-color: var(--pages-danger-9, #dc2626); color: var(--pages-danger-9, #dc2626); }
    .result { margin-top: var(--pages-space-3, 12px); padding: var(--pages-space-2, 8px); border-radius: var(--pages-radius-sm, 4px); font-size: var(--pages-font-size-sm, 13px); }
    .result.ok { background: var(--pages-success-2, #e6f7ed); color: var(--pages-success-11, #0d5a2e); }
    .result.err { background: var(--pages-danger-2, #fee); color: var(--pages-danger-11, #991b1b); }
  `;

  private async _action(action: string) {
    const caseId = field(this.item, 'caseId');
    if (!caseId) return;
    this._result = null;
    try {
      const resp = await fetch(`/api/cases/${caseId}/actions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action }),
      });
      this._result = resp.ok ? 'ok' : 'err';
    } catch {
      this._result = 'err';
    }
  }

  override render() {
    if (!this.item) return html`<p>No case selected</p>`;
    return html`
      <div class="actions">
        <button class="btn-approve" @click=${() => this._action('approve')}>Approve</button>
        <button class="btn-reject" @click=${() => this._action('reject')}>Reject</button>
      </div>
      ${this._result ? html`<div class="result ${this._result}">${this._result === 'ok' ? 'Action applied' : 'Action failed'}</div>` : ''}
    `;
  }
}

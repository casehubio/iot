import { LitElement, html, css } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { buildGraphViewModel, type GraphViewModel } from './topology-graph-model.js';
import type { TopologyNode, TopologyEdge } from './topology-tree-model.js';

function emitPagesEvent<T>(target: EventTarget, topic: string, payload: T): void {
  target.dispatchEvent(new CustomEvent('pages-event', {
    bubbles: true, composed: true,
    detail: { topic, payload },
  }));
}

@customElement('iot-topology-graph')
export class IoTTopologyGraph extends LitElement {
  @property({ type: Array }) nodes: TopologyNode[] = [];
  @property({ type: Array }) edges: TopologyEdge[] = [];
  @property({ attribute: 'selection-topic' }) selectionTopic = 'topology-device';

  @state() private _viewModel: GraphViewModel | null = null;

  override updated(changed: Map<PropertyKey, unknown>): void {
    if (changed.has('nodes') || changed.has('edges')) {
      if (this.nodes.length === 0) {
        this._viewModel = null;
      } else {
        this._viewModel = buildGraphViewModel(this.nodes, this.edges);
      }
    }
  }

  private _onNodeClick(deviceId: string): void {
    emitPagesEvent(this, this.selectionTopic, { deviceId });
  }

  static override styles = css`
    :host { display: flex; flex-direction: column; height: 100%; min-height: 400px; }
    .empty { display: flex; align-items: center; justify-content: center;
      height: 100%; color: var(--pages-text-tertiary, #999); font-style: italic; }
    .graph-grid { display: flex; flex-wrap: wrap; gap: 12px; padding: 12px; }
    .graph-node { display: flex; flex-direction: column; align-items: center; gap: 4px;
      padding: 12px 16px; border-radius: 8px; cursor: pointer; min-width: 120px; }
    .graph-node:hover { filter: brightness(0.95); }
    .node-label { font-weight: 600; font-size: 13px; color: var(--pages-text-color, #333); }
    .node-class { font-size: 11px; color: var(--pages-text-secondary, #666); }
    .node-tooltip { font-size: 10px; color: var(--pages-text-tertiary, #999); }
    .edges-section { padding: 8px 12px; font-size: 12px; color: var(--pages-text-secondary, #666); }
    .edge-label { padding: 2px 0; }
  `;

  override render() {
    if (this._viewModel == null) {
      return html`<div class="empty">No devices in topology</div>`;
    }
    return html`
      <div class="graph-grid">
        ${this._viewModel.nodes.map(node => {
          const dec = this._viewModel!.decorations.get(node.id);
          return html`
            <div class="graph-node"
              style="border: 2px ${dec?.borderStyle ?? 'solid'} ${dec?.borderColor ?? '#9ca3af'}; background: ${dec?.borderColor ?? '#9ca3af'}11"
              title=${dec?.tooltip ?? ''}
              @click=${() => this._onNodeClick(node.id)}>
              <span class="node-label">${node.label}</span>
              <span class="node-class">${node.deviceClass}</span>
            </div>
          `;
        })}
      </div>
      ${this._viewModel.edges.length > 0 ? html`
        <div class="edges-section">
          ${this._viewModel.edges.map(edge => html`
            <div class="edge-label">${edge.source} → ${edge.target} (${edge.label})</div>
          `)}
        </div>
      ` : ''}
    `;
  }
}

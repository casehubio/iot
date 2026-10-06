import { LitElement, html, css, type PropertyValues } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { SSEManager, type SSEEvent } from '@casehubio/pages-data';
import { buildGraphViewModel, type GraphViewModel } from './topology-graph-model.js';
import { applyBindingEvent, type TopologyNode, type TopologyEdge, type HighlightState } from './topology-tree-model.js';

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
  @property({ attribute: 'sse-endpoint' }) sseEndpoint?: string;

  @state() private _viewModel: GraphViewModel | null = null;
  @state() private _highlightMap: Record<string, HighlightState> = {};

  private _sseManager = new SSEManager();
  private _sseHandler = (event: SSEEvent) => {
    const data = event.data as { operation?: string; nodes?: TopologyNode[]; edges?: TopologyEdge[]; binding?: Record<string, unknown> };
    if (data.operation?.startsWith('binding-')) {
      this._highlightMap = applyBindingEvent(this._highlightMap, {
        operation: data.operation,
        binding: data.binding ?? {},
      });
      if (data.operation === 'binding-complete') {
        const executionId = (data.binding?.executionId as string) ?? '';
        setTimeout(() => {
          const cleared = { ...this._highlightMap };
          for (const [id, hl] of Object.entries(cleared)) {
            if (hl.executionId === executionId) delete cleared[id];
          }
          this._highlightMap = cleared;
        }, 1500);
      }
    } else if (data.operation === 'snapshot' || data.operation === 'update') {
      if (data.nodes) this.nodes = data.nodes;
      if (data.edges) this.edges = data.edges;
    }
  };

  override disconnectedCallback(): void {
    if (this.sseEndpoint) {
      this._sseManager.unsubscribe(this.sseEndpoint, this._sseHandler);
    }
    super.disconnectedCallback();
  }

  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('sseEndpoint')) {
      const old = changed.get('sseEndpoint') as string | undefined;
      if (old) this._sseManager.unsubscribe(old, this._sseHandler);
      if (this.sseEndpoint) this._sseManager.subscribe(this.sseEndpoint, this._sseHandler);
    }
  }

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
    .graph-node.highlight-active {
      box-shadow: 0 0 8px var(--pages-accent-color, #1a73e8);
      animation: pulse-glow 1.5s ease-in-out infinite;
    }
    .graph-node.highlight-provisioned {
      border-color: var(--pages-success-color, #22c55e) !important;
      box-shadow: 0 0 4px var(--pages-success-color, #22c55e);
    }
    .graph-node.highlight-failed {
      border-color: var(--pages-error-color, #ef4444) !important;
      box-shadow: 0 0 4px var(--pages-error-color, #ef4444);
    }
    .scenario-summary {
      padding: 8px 12px; font-size: 12px; color: var(--pages-accent-color, #1a73e8);
      font-weight: 600; border-bottom: 1px solid var(--pages-border-color, #e5e7eb);
    }
    @keyframes pulse-glow {
      0%, 100% { box-shadow: 0 0 8px var(--pages-accent-color, #1a73e8); }
      50% { box-shadow: 0 0 16px var(--pages-accent-color, #1a73e8); }
    }
  `;

  override render() {
    if (this._viewModel == null) {
      return html`<div class="empty">No devices in topology</div>`;
    }
    const highlightedCount = Object.keys(this._highlightMap).length;
    const zoneCount = highlightedCount > 0
      ? new Set(this._viewModel.nodes
          .filter(n => this._highlightMap[n.id])
          .map(n => this.nodes.find(orig => orig.deviceId === n.id)?.locationPath.join('/') ?? 'unknown'))
        .size
      : 0;
    return html`
      ${highlightedCount > 0 ? html`
        <div class="scenario-summary">${highlightedCount} device${highlightedCount !== 1 ? 's' : ''} in ${zoneCount} zone${zoneCount !== 1 ? 's' : ''} affected</div>
      ` : ''}
      <div class="graph-grid">
        ${this._viewModel.nodes.map(node => {
          const dec = this._viewModel!.decorations.get(node.id);
          const highlight = this._highlightMap[node.id];
          const highlightClass = highlight ? `highlight-${highlight.status}` : '';
          return html`
            <div class="graph-node ${highlightClass}"
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

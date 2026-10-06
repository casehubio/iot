import { LitElement, html, css, nothing, type PropertyValues } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { TemplateResult } from 'lit';
import { SSEManager, type SSEEvent } from '@casehubio/pages-data';
import { buildTree, formatAggregateSummary, computeZoneHighlight, formatZoneHighlightSummary, applyBindingEvent, DRIFT_COLORS, type TopologyNode, type TopologyAggregate, type TreeBranch, type HighlightState } from './topology-tree-model.js';

function emitPagesEvent<T>(target: EventTarget, topic: string, payload: T): void {
  target.dispatchEvent(new CustomEvent('pages-event', {
    bubbles: true, composed: true,
    detail: { topic, payload },
  }));
}

@customElement('iot-topology-tree')
export class IoTTopologyTree extends LitElement {
  @property({ type: Array }) nodes: TopologyNode[] = [];
  @property({ type: Object }) locationAggregates: Record<string, TopologyAggregate> = {};
  @property({ attribute: 'selection-topic' }) selectionTopic = 'topology-device';
  @property({ attribute: 'sse-endpoint' }) sseEndpoint?: string;

  @state() private _expanded = new Set<string>();
  @state() private _highlightMap: Record<string, HighlightState> = {};

  private _sseManager = new SSEManager();
  private _sseHandler = (event: SSEEvent) => {
    const data = event.data as { operation?: string; nodes?: TopologyNode[]; locationAggregates?: Record<string, TopologyAggregate>; binding?: Record<string, unknown> };
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
      if (data.locationAggregates) this.locationAggregates = data.locationAggregates;
    }
  };

  static override styles = css`
    :host { display: block; font-family: var(--pages-font-family, sans-serif); font-size: 13px; }
    .empty { color: var(--pages-text-tertiary, #999); font-style: italic; padding: 12px; }
    ul { list-style: none; padding-left: 20px; margin: 0; }
    ul[role="tree"] { padding-left: 0; }
    li { padding: 2px 0; }
    .branch-node, .device-node { display: flex; align-items: center; gap: 6px; padding: 4px 8px;
      border-radius: 4px; cursor: pointer; }
    .branch-node:hover, .device-node:hover { background: var(--pages-hover-color, #f3f4f6); }
    .branch-node:focus, .device-node:focus { outline: 2px solid var(--pages-accent-color, #1a73e8); outline-offset: -2px; }
    .branch-name { font-weight: 600; color: var(--pages-text-color, #333); }
    .toggle { width: 16px; text-align: center; font-size: 11px; color: var(--pages-text-secondary, #666); }
    .aggregate { font-size: 11px; color: var(--pages-text-tertiary, #999); }
    .device-label { color: var(--pages-text-color, #333); }
    .drift-badge { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
    .drift-badge.unmonitored { border: 1.5px solid #22c55e; background: transparent; }
    .drift-detail { font-size: 11px; color: var(--pages-text-tertiary, #999); font-style: italic; }
    .device-class { font-size: 11px; color: var(--pages-text-secondary, #666);
      background: var(--pages-accent-subtle, #e8f0fe); padding: 1px 6px; border-radius: 4px; }
    .device-node.highlight-active {
      animation: pulse-highlight 1.5s ease-in-out infinite;
      border-left: 3px solid var(--pages-accent-color, #1a73e8);
    }
    .device-node.highlight-provisioned { border-left: 3px solid var(--pages-success-color, #22c55e); }
    .device-node.highlight-failed { border-left: 3px solid var(--pages-error-color, #ef4444); }
    .branch-node.zone-highlight { background: var(--pages-accent-color-subtle, #e8f0fe); }
    .zone-badge { font-size: 11px; color: var(--pages-accent-color, #1a73e8); font-weight: 600; }
    @keyframes pulse-highlight {
      0%, 100% { border-left-color: var(--pages-accent-color, #1a73e8); opacity: 1; }
      50% { border-left-color: var(--pages-accent-color, #1a73e8); opacity: 0.5; }
    }
  `;

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

  private _toggle(path: string): void {
    const next = new Set(this._expanded);
    if (next.has(path)) next.delete(path); else next.add(path);
    this._expanded = next;
  }

  private _selectDevice(node: TopologyNode): void {
    emitPagesEvent(this, this.selectionTopic, { deviceId: node.deviceId });
  }

  private _renderBranch(branch: TreeBranch): TemplateResult {
    const expanded = this._expanded.has(branch.path);
    const aggregate = this.locationAggregates[branch.path];
    const summary = aggregate ? formatAggregateSummary(aggregate) : '';
    const zoneHighlight = computeZoneHighlight(branch, this._highlightMap);

    return html`
      <li role="treeitem" aria-expanded=${expanded}>
        <div class="branch-node ${zoneHighlight ? 'zone-highlight' : ''}" tabindex="-1"
          @click=${() => this._toggle(branch.path)}
          @keydown=${(e: KeyboardEvent) => {
            if (e.key === 'Enter' || e.key === 'ArrowRight') { if (!expanded) this._toggle(branch.path); }
            if (e.key === 'ArrowLeft') { if (expanded) this._toggle(branch.path); }
          }}>
          <span class="toggle">${expanded ? '▼' : '▶'}</span>
          <span class="branch-name">${branch.name}</span>
          ${zoneHighlight
            ? html`<span class="zone-badge">${formatZoneHighlightSummary(zoneHighlight)}</span>`
            : !expanded && summary
              ? html`<span class="aggregate">${summary}</span>`
              : nothing}
        </div>
        ${expanded ? html`
          <ul role="group">
            ${[...branch.children.values()].map(child => this._renderBranch(child))}
            ${branch.devices.map(device => this._renderDevice(device))}
          </ul>
        ` : nothing}
      </li>
    `;
  }

  private _renderDevice(node: TopologyNode): TemplateResult {
    const color = DRIFT_COLORS[node.driftStatus] ?? DRIFT_COLORS['UNKNOWN']!;
    const isUnmonitored = node.driftStatus === 'UNMONITORED';
    const highlight = this._highlightMap[node.deviceId];
    const highlightClass = highlight ? `highlight-${highlight.status}` : '';
    return html`
      <li role="treeitem" aria-selected="false">
        <div class="device-node ${highlightClass}" tabindex="-1"
          @click=${() => this._selectDevice(node)}
          @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') this._selectDevice(node); }}>
          <span class="drift-badge ${isUnmonitored ? 'unmonitored' : ''}"
                style="${isUnmonitored ? '' : `background: ${color}`}"
                title=${node.driftStatus}></span>
          <span class="device-label">${node.label}</span>
          <span class="device-class">${node.deviceClass}</span>
          ${node.driftDetail ? html`<span class="drift-detail">${node.driftDetail}</span>` : nothing}
        </div>
      </li>
    `;
  }

  override render() {
    if (this.nodes.length === 0) {
      return html`<div class="empty">No devices in topology</div>`;
    }
    const tree = buildTree(this.nodes);
    return html`
      <ul role="tree" aria-label="Device topology">
        ${[...tree.children.values()].map(child => this._renderBranch(child))}
        ${tree.devices.map(device => this._renderDevice(device))}
      </ul>
    `;
  }
}

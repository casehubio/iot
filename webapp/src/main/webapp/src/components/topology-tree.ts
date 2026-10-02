import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import type { TemplateResult } from 'lit';
import { buildTree, formatAggregateSummary, DRIFT_COLORS, type TopologyNode, type TopologyAggregate, type TreeBranch } from './topology-tree-model.js';

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

  @state() private _expanded = new Set<string>();

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
  `;

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

    return html`
      <li role="treeitem" aria-expanded=${expanded}>
        <div class="branch-node" tabindex="-1"
          @click=${() => this._toggle(branch.path)}
          @keydown=${(e: KeyboardEvent) => {
            if (e.key === 'Enter' || e.key === 'ArrowRight') { if (!expanded) this._toggle(branch.path); }
            if (e.key === 'ArrowLeft') { if (expanded) this._toggle(branch.path); }
          }}>
          <span class="toggle">${expanded ? '▼' : '▶'}</span>
          <span class="branch-name">${branch.name}</span>
          ${!expanded && summary
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
    return html`
      <li role="treeitem" aria-selected="false">
        <div class="device-node" tabindex="-1"
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

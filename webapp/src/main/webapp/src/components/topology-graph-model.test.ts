import { describe, it, expect } from 'vitest';
import { buildGraphViewModel } from './topology-graph-model.js';
import type { TopologyNode, TopologyEdge } from './topology-tree-model.js';

function makeNode(overrides: Partial<TopologyNode> = {}): TopologyNode {
  return {
    deviceId: 'dev-1', label: 'Light 1', deviceClass: 'LIGHT',
    locationPath: [], available: true,
    lastUpdated: new Date().toISOString(), driftStatus: 'CONVERGED',
    driftDetail: null, ...overrides,
  };
}

describe('buildGraphViewModel', () => {
  it('creates graph nodes from topology nodes', () => {
    const vm = buildGraphViewModel([
      makeNode({ deviceId: 'a', label: 'Light A' }),
      makeNode({ deviceId: 'b', label: 'Light B' }),
    ], []);
    expect(vm.nodes.length).toBe(2);
    expect(vm.nodes[0]!.id).toBe('a');
    expect(vm.nodes[0]!.label).toBe('Light A');
  });

  it('creates graph edges from topology edges', () => {
    const edges: TopologyEdge[] = [
      { sourceDeviceId: 'a', targetDeviceId: 'b', label: 'depends-on' },
    ];
    const vm = buildGraphViewModel([
      makeNode({ deviceId: 'a' }),
      makeNode({ deviceId: 'b' }),
    ], edges);
    expect(vm.edges.length).toBe(1);
    expect(vm.edges[0]!.source).toBe('a');
    expect(vm.edges[0]!.target).toBe('b');
  });

  it('maps drift status to border color', () => {
    const vm = buildGraphViewModel([
      makeNode({ deviceId: 'a', driftStatus: 'UNEXPECTED_DRIFT' }),
    ], []);
    expect(vm.decorations.get('a')!.borderColor).toBe('#ef4444');
    expect(vm.decorations.get('a')!.borderStyle).toBe('solid');
  });

  it('uses dashed border for unmonitored devices', () => {
    const vm = buildGraphViewModel([
      makeNode({ deviceId: 'a', driftStatus: 'UNMONITORED' }),
    ], []);
    expect(vm.decorations.get('a')!.borderStyle).toBe('dashed');
  });

  it('uses driftDetail as tooltip when available', () => {
    const vm = buildGraphViewModel([
      makeNode({ deviceId: 'a', driftStatus: 'PERMITTED_DRIFT', driftDetail: 'revert in 12m' }),
    ], []);
    expect(vm.decorations.get('a')!.tooltip).toBe('revert in 12m');
  });

  it('falls back to driftStatus as tooltip', () => {
    const vm = buildGraphViewModel([
      makeNode({ deviceId: 'a', driftStatus: 'CONVERGED' }),
    ], []);
    expect(vm.decorations.get('a')!.tooltip).toBe('CONVERGED');
  });

  it('returns empty graph for empty input', () => {
    const vm = buildGraphViewModel([], []);
    expect(vm.nodes.length).toBe(0);
    expect(vm.edges.length).toBe(0);
    expect(vm.decorations.size).toBe(0);
  });
});

import { describe, it, expect } from 'vitest';
import { buildTree, formatAggregateSummary, type TopologyNode, type TopologyAggregate } from './topology-tree-model.js';

function makeNode(overrides: Partial<TopologyNode> = {}): TopologyNode {
  return {
    deviceId: 'dev-1', label: 'Light 1', deviceClass: 'LIGHT',
    locationPath: ['HQ', 'Floor1', 'Kitchen'], available: true,
    lastUpdated: new Date().toISOString(), driftStatus: 'CONVERGED',
    driftDetail: null, ...overrides,
  };
}

describe('buildTree', () => {
  it('creates empty root for empty input', () => {
    const tree = buildTree([]);
    expect(tree.children.size).toBe(0);
    expect(tree.devices.length).toBe(0);
  });

  it('builds hierarchy from locationPath segments', () => {
    const tree = buildTree([
      makeNode({ deviceId: 'd1', locationPath: ['HQ', 'Floor1'] }),
      makeNode({ deviceId: 'd2', locationPath: ['HQ', 'Floor2'] }),
    ]);
    expect(tree.children.has('HQ')).toBe(true);
    const hq = tree.children.get('HQ')!;
    expect(hq.children.has('Floor1')).toBe(true);
    expect(hq.children.has('Floor2')).toBe(true);
    expect(hq.children.get('Floor1')!.devices.length).toBe(1);
    expect(hq.children.get('Floor2')!.devices.length).toBe(1);
  });

  it('groups devices with empty locationPath under Unassigned', () => {
    const tree = buildTree([makeNode({ deviceId: 'd1', locationPath: [] })]);
    expect(tree.children.has('Unassigned')).toBe(true);
    expect(tree.children.get('Unassigned')!.devices.length).toBe(1);
  });

  it('places multiple devices in same location branch', () => {
    const tree = buildTree([
      makeNode({ deviceId: 'd1', locationPath: ['HQ', 'Room1'] }),
      makeNode({ deviceId: 'd2', locationPath: ['HQ', 'Room1'] }),
    ]);
    const room = tree.children.get('HQ')!.children.get('Room1')!;
    expect(room.devices.length).toBe(2);
  });

  it('assigns correct path to each branch', () => {
    const tree = buildTree([
      makeNode({ deviceId: 'd1', locationPath: ['A', 'B', 'C'] }),
    ]);
    expect(tree.children.get('A')!.path).toBe('A');
    expect(tree.children.get('A')!.children.get('B')!.path).toBe('A/B');
    expect(tree.children.get('A')!.children.get('B')!.children.get('C')!.path).toBe('A/B/C');
  });

  it('handles single-segment location', () => {
    const tree = buildTree([makeNode({ deviceId: 'd1', locationPath: ['Kitchen'] })]);
    expect(tree.children.has('Kitchen')).toBe(true);
    expect(tree.children.get('Kitchen')!.devices.length).toBe(1);
  });
});

describe('formatAggregateSummary', () => {
  it('formats total only when no issues', () => {
    const agg: TopologyAggregate = {
      total: 5, converged: 5, permittedDrift: 0, unexpectedDrift: 0,
      absent: 0, unknown: 0, unmonitored: 0,
    };
    expect(formatAggregateSummary(agg)).toBe('5 devices');
  });

  it('includes drift counts when present', () => {
    const agg: TopologyAggregate = {
      total: 10, converged: 7, permittedDrift: 2, unexpectedDrift: 1,
      absent: 0, unknown: 0, unmonitored: 0,
    };
    const result = formatAggregateSummary(agg);
    expect(result).toContain('10 devices');
    expect(result).toContain('2 permitted drifts');
    expect(result).toContain('1 unexpected');
  });

  it('includes absent count', () => {
    const agg: TopologyAggregate = {
      total: 3, converged: 1, permittedDrift: 0, unexpectedDrift: 0,
      absent: 2, unknown: 0, unmonitored: 0,
    };
    expect(formatAggregateSummary(agg)).toContain('2 absent');
  });

  it('uses singular for 1 device', () => {
    const agg: TopologyAggregate = {
      total: 1, converged: 1, permittedDrift: 0, unexpectedDrift: 0,
      absent: 0, unknown: 0, unmonitored: 0,
    };
    expect(formatAggregateSummary(agg)).toBe('1 device');
  });
});

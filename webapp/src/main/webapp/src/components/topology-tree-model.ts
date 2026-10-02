export interface TopologyNode {
  deviceId: string;
  label: string;
  deviceClass: string;
  locationPath: string[];
  available: boolean;
  lastUpdated: string;
  driftStatus: string;
  driftDetail: string | null;
}

export interface TopologyEdge {
  sourceDeviceId: string;
  targetDeviceId: string;
  label: string;
}

export interface TopologyAggregate {
  total: number;
  converged: number;
  permittedDrift: number;
  unexpectedDrift: number;
  absent: number;
  unknown: number;
  unmonitored: number;
}

export interface TreeBranch {
  name: string;
  path: string;
  children: Map<string, TreeBranch>;
  devices: TopologyNode[];
}

export const DRIFT_COLORS: Record<string, string> = {
  CONVERGED: '#22c55e',
  PERMITTED_DRIFT: '#f59e0b',
  UNEXPECTED_DRIFT: '#ef4444',
  ABSENT: '#ef4444',
  UNKNOWN: '#9ca3af',
  UNMONITORED: 'transparent',
};

export function buildTree(nodes: TopologyNode[]): TreeBranch {
  const root: TreeBranch = { name: '', path: '', children: new Map(), devices: [] };
  for (const node of nodes) {
    const path = node.locationPath;
    if (path.length === 0) {
      let unassigned = root.children.get('Unassigned');
      if (!unassigned) {
        unassigned = { name: 'Unassigned', path: 'Unassigned', children: new Map(), devices: [] };
        root.children.set('Unassigned', unassigned);
      }
      unassigned.devices.push(node);
    } else {
      let current = root;
      for (let i = 0; i < path.length; i++) {
        const segment = path[i]!;
        const fullPath = path.slice(0, i + 1).join('/');
        let child = current.children.get(segment);
        if (!child) {
          child = { name: segment, path: fullPath, children: new Map(), devices: [] };
          current.children.set(segment, child);
        }
        current = child;
      }
      current.devices.push(node);
    }
  }
  return root;
}

export function formatAggregateSummary(aggregate: TopologyAggregate): string {
  const parts: string[] = [];
  parts.push(`${aggregate.total} device${aggregate.total !== 1 ? 's' : ''}`);
  if (aggregate.permittedDrift > 0) parts.push(`${aggregate.permittedDrift} permitted drift${aggregate.permittedDrift !== 1 ? 's' : ''}`);
  if (aggregate.unexpectedDrift > 0) parts.push(`${aggregate.unexpectedDrift} unexpected`);
  if (aggregate.absent > 0) parts.push(`${aggregate.absent} absent`);
  return parts.join(', ');
}

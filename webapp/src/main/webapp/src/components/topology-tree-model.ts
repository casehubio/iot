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

export interface HighlightState {
  status: 'active' | 'provisioned' | 'failed';
  executionId: string;
  stepName: string;
}

export interface ZoneHighlight {
  total: number;
  counts: { active: number; provisioned: number; failed: number };
}

export interface BindingEventData {
  operation: string;
  binding: Record<string, unknown>;
}

export function computeZoneHighlight(
  branch: TreeBranch,
  highlightMap: Record<string, HighlightState>,
): ZoneHighlight | null {
  const highlighted = branch.devices.filter(d => highlightMap[d.deviceId]);
  if (highlighted.length < 2) return null;
  const counts = { active: 0, provisioned: 0, failed: 0 };
  for (const d of highlighted) {
    counts[highlightMap[d.deviceId]!.status]++;
  }
  return { total: highlighted.length, counts };
}

export function formatZoneHighlightSummary(zone: ZoneHighlight): string {
  const parts: string[] = [];
  if (zone.counts.active > 0) parts.push(`${zone.counts.active} active`);
  if (zone.counts.provisioned > 0) parts.push(`${zone.counts.provisioned} provisioned`);
  if (zone.counts.failed > 0) parts.push(`${zone.counts.failed} failed`);
  return parts.join(', ');
}

export function applyBindingEvent(
  current: Record<string, HighlightState>,
  data: BindingEventData,
): Record<string, HighlightState> {
  const next = { ...current };
  const binding = data.binding;

  switch (data.operation) {
    case 'binding-start': {
      const deviceIds = binding.deviceIds as string[];
      const executionId = binding.executionId as string;
      const stepName = binding.stepName as string;
      for (const id of deviceIds) {
        next[id] = { status: 'active', executionId, stepName };
      }
      break;
    }
    case 'binding-update': {
      const deviceId = binding.deviceId as string;
      const status = (binding.status as string) === 'PROVISIONED' ? 'provisioned' as const : 'failed' as const;
      const existing = current[deviceId];
      if (existing) {
        next[deviceId] = { ...existing, status };
      }
      break;
    }
    case 'binding-clear': {
      const executionId = binding.executionId as string;
      for (const [id, hl] of Object.entries(next)) {
        if (hl.executionId === executionId) delete next[id];
      }
      break;
    }
  }
  return next;
}

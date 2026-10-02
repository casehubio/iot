import type { TopologyNode, TopologyEdge } from './topology-tree-model.js';

export interface GraphViewModel {
  nodes: GraphViewNode[];
  edges: GraphViewEdge[];
  decorations: Map<string, GraphViewDecoration>;
}

export interface GraphViewNode {
  id: string;
  label: string;
  deviceClass: string;
  driftStatus: string;
  available: boolean;
}

export interface GraphViewEdge {
  id: string;
  source: string;
  target: string;
  label: string;
}

export interface GraphViewDecoration {
  borderColor: string;
  borderStyle: string;
  tooltip: string;
}

const DRIFT_BORDER_COLORS: Record<string, string> = {
  CONVERGED: '#22c55e',
  PERMITTED_DRIFT: '#f59e0b',
  UNEXPECTED_DRIFT: '#ef4444',
  ABSENT: '#ef4444',
  UNKNOWN: '#9ca3af',
  UNMONITORED: '#22c55e',
};

export function buildGraphViewModel(nodes: TopologyNode[], edges: TopologyEdge[]): GraphViewModel {
  const graphNodes: GraphViewNode[] = nodes.map(n => ({
    id: n.deviceId,
    label: n.label,
    deviceClass: n.deviceClass,
    driftStatus: n.driftStatus,
    available: n.available,
  }));

  const graphEdges: GraphViewEdge[] = edges.map((e, i) => ({
    id: `edge-${i}`,
    source: e.sourceDeviceId,
    target: e.targetDeviceId,
    label: e.label,
  }));

  const decorations = new Map<string, GraphViewDecoration>();
  for (const node of nodes) {
    const borderColor = DRIFT_BORDER_COLORS[node.driftStatus] ?? '#9ca3af';
    decorations.set(node.deviceId, {
      borderColor,
      borderStyle: node.driftStatus === 'UNMONITORED' ? 'dashed' : 'solid',
      tooltip: node.driftDetail ?? node.driftStatus,
    });
  }

  return { nodes: graphNodes, edges: graphEdges, decorations };
}

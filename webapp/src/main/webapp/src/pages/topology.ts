import { page, rows, columns, hostPanel, html } from "@casehubio/pages-ui";

export function topologyPage() {
  return page("Topology",
    rows(
      columns([6, 6],
        [hostPanel("iot-topology-tree", {
          endpoint: "/api/topology",
          sseEndpoint: "/api/topology/stream",
          selectionTopic: "topology-device",
        })],
        [hostPanel("iot-topology-graph", {
          endpoint: "/api/topology",
          sseEndpoint: "/api/topology/stream",
          selectionTopic: "topology-device",
        })],
      ),
    ),
  );
}

import { page, rows, columns, selector, title, hostPanel, html, lookup, groupBy, col } from "@casehubio/pages-ui";
import { dataSetId, columnId } from "@casehubio/pages-data/dist/dataset/types.js";
import { deviceTable } from "../components/device-table";

export function devicesPage() {
  return page("Devices",
    rows(
      // KPI row — blocks-ui component via hostPanel
      hostPanel("blocks-kpi-metric-row", {
        endpoint: "/api/devices/kpi",
        columns: 4,
        density: "comfortable",
        refreshInterval: 30000,
      }),

      // Device class filter
      columns([2, 10],
        [selector({
          title: "Device Class",
          filter: { enabled: true },
          lookup: lookup("devices", groupBy("deviceClass", col("deviceClass"))),
          subtype: "labels",
        })],
        [html("")], // spacer
      ),

      // Device table
      deviceTable("devices"),

      // Device detail sub-page — tabbed via blocks-detail-pane
      page("Device Detail",
        rows(
          title("Device Details"),

          hostPanel("blocks-detail-pane", {
            selectionTopic: "device",
            tabs: [
              { id: "state", label: "State", tagName: "iot-device-state-tab", order: 0 },
              { id: "history", label: "History", tagName: "iot-device-history-tab", order: 1 },
              { id: "actions", label: "Actions", tagName: "iot-device-actions-tab", order: 2 },
            ],
          }),
        ),
        {
          dataScope: { dataset: dataSetId("devices"), idColumn: columnId("deviceId") },
        },
      ),
    ),
  );
}

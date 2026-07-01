import { page, rows, columns, panel, selector, title, table, metric, html, lookup, groupBy, col, filterBy, sortBy } from "@casehubio/pages-ui";
import { dataSetId, columnId } from "@casehubio/pages-data/dist/dataset/types.js";
import { deviceKpiRow } from "../components/device-kpi-row";
import { deviceTable } from "../components/device-table";

export function devicesPage() {
  return page("Devices",
    rows(
      // KPI row
      deviceKpiRow("devices"),

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

      // Device detail sub-page
      page("Device Detail",
        rows(
          title("Device Details"),

          // Current state metrics
          columns([3, 3, 3, 3],
            [metric({
              title: "State",
              lookup: lookup("devices", groupBy(null, col("available"))),
              subtype: "card",
            })],
            [metric({
              title: "Provider",
              lookup: lookup("devices", groupBy(null, col("providerId"))),
              subtype: "plain-text",
            })],
            [metric({
              title: "Device Class",
              lookup: lookup("devices", groupBy(null, col("deviceClass"))),
              subtype: "plain-text",
            })],
            [metric({
              title: "Last Updated",
              lookup: lookup("devices", groupBy(null, col("lastUpdated"))),
              subtype: "plain-text",
            })],
          ),

          // Action buttons section
          panel("Actions", html(`
            <div style="display: flex; gap: 8px; padding: 16px;">
              <button onclick="deviceCommand('on')">Turn On</button>
              <button onclick="deviceCommand('off')">Turn Off</button>
              <button onclick="deviceCommand('lock')">Lock</button>
              <button onclick="deviceCommand('unlock')">Unlock</button>
            </div>
          `)),

          // State change history timeline
          panel("State History", table({
            title: "State Change Timeline",
            sortable: true,
            pageSize: 10,
            lookup: lookup("device-history", sortBy("occurredAt", "DESCENDING")),
          })),
        ),
        {
          dataScope: { dataset: dataSetId("devices"), idColumn: columnId("deviceId") },
        },
      ),
    ),
  );
}

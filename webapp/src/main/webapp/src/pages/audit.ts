import { page, rows, tabs, panel, table, columns, selector, datePicker, lookup, groupBy, col, sortBy, hostPanel } from "@casehubio/pages-ui";
import { renderAuditDetail, auditRowKey } from "../renderers/audit-detail.js";

export function auditPage() {
  return page("Audit",
    rows(
      tabs(
        ["Table",
          columns([2, 2, 2, 6],
            [selector({
              title: "Event Type",
              filter: { enabled: true, group: "audit" },
              lookup: lookup("audit", groupBy("eventType", col("eventType"))),
              subtype: "dropdown",
            })],
            [selector({
              title: "Device",
              filter: { enabled: true, group: "audit" },
              lookup: lookup("audit", groupBy("deviceId", col("deviceId"))),
              subtype: "dropdown",
            })],
            [datePicker({ field: "dateFrom", label: "From Date" })],
            [datePicker({ field: "dateTo", label: "To Date" })],
          ),
          panel("Audit Trail", table({
            title: "Event History",
            sortable: true,
            pageSize: 25,
            csvExport: true,
            filter: { listening: true, group: "audit" },
            lookup: lookup("audit", sortBy("timestamp", "DESCENDING")),
          })),
        ],
        ["Trail", hostPanel("event-trail", {
          endpoint: "/api/bridge/audit?limit=500",
          recordsPath: "records",
          columnDefs: [
            { id: "eventType", label: "Event", getValue: (r: any) => r.eventType },
            { id: "deviceId", label: "Device", getValue: (r: any) => r.deviceId },
            { id: "correlationId", label: "Correlation", getValue: (r: any) => r.correlationId },
            { id: "occurredAt", label: "Time", getValue: (r: any) => r.occurredAt },
          ],
          chipField: "eventType",
          chipValues: [
            "STATE_CHANGE", "COMMAND_SENT", "COMMAND_RESPONSE",
            "STATE_SNAPSHOT", "PROVIDER_STATUS_CHANGE",
            "AGENT_CONNECTED", "AGENT_DISCONNECTED",
            "REPLAYED_STATE_CHANGE",
          ],
          entityField: "deviceId",
          entityLabel: "Device",
          showDateRange: true,
          csvExport: true,
          getRowDetail: renderAuditDetail,
          getRowKey: auditRowKey,
        })],
      ),
    ),
  );
}

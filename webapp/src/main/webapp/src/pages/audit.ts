import { page, rows, panel, table, columns, selector, datePicker, lookup, groupBy, col, sortBy } from "@casehubio/pages-ui";

export function auditPage() {
  return page("Audit",
    rows(
      // Filters
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
    ),
  );
}

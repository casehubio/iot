import { table, withId, lookup, sortBy } from "@casehubio/pages-ui";

export function deviceTable(datasetId: string) {
  return withId("device-table", table({
    title: "Devices",
    sortable: true,
    pageSize: 20,
    csvExport: true,
    filter: { enabled: true, listening: true },
    lookup: lookup(datasetId, sortBy("lastUpdated", "DESCENDING")),
  }));
}

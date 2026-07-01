import { columns, metric, lookup, groupBy, count, distinct, filterBy } from "@casehubio/pages-ui";

export function deviceKpiRow(datasetId: string) {
  return columns([3, 3, 3, 3],
    [metric({
      title: "Total Devices",
      lookup: lookup(datasetId, groupBy(null, count("deviceId"))),
      subtype: "card",
    })],
    [metric({
      title: "Online",
      lookup: lookup(datasetId, filterBy("available", "EQUALS_TO", "true"), groupBy(null, count("deviceId"))),
      subtype: "card",
    })],
    [metric({
      title: "Providers",
      lookup: lookup(datasetId, groupBy(null, distinct("providerId"))),
      subtype: "card",
    })],
    [metric({
      title: "Active Alerts",
      lookup: lookup("situations-active", groupBy(null, count("situationId"))),
      subtype: "card",
    })],
  );
}

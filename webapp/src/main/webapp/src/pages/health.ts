import { page, rows, columns, panel, metric, table, lookup, groupBy, col, count } from "@casehubio/pages-ui";

export function healthPage() {
  return page("Health",
    rows(
      // KPI metrics
      columns([3, 3, 3, 3],
        [panel("Providers", metric({
          title: "Connected Providers",
          lookup: lookup("providers", groupBy(null, count("providerId"))),
          subtype: "card",
          refresh: { interval: 10000 },
        }))],
        [panel("Bridge", metric({
          title: "Bridge Connections",
          lookup: lookup("health", groupBy(null, col("bridgeConnectionCount"))),
          subtype: "card",
          refresh: { interval: 10000 },
        }))],
        [panel("Situations", metric({
          title: "Active Situations",
          lookup: lookup("health", groupBy(null, col("activeSituationCount"))),
          subtype: "card",
          refresh: { interval: 10000 },
        }))],
        [panel("Cases", metric({
          title: "Open Cases",
          lookup: lookup("health", groupBy(null, col("openCaseCount"))),
          subtype: "card",
          refresh: { interval: 10000 },
        }))],
      ),

      // Provider status table
      panel("Provider Status", table({
        title: "Providers",
        sortable: true,
        lookup: lookup("providers"),
        refresh: { interval: 10000 },
      })),
    ),
  );
}

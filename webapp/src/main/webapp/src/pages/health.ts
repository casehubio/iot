import { page, rows, panel, table, hostPanel, lookup } from "@casehubio/pages-ui";

export function healthPage() {
  return page("Health",
    rows(
      // KPI row — blocks-ui component via hostPanel
      hostPanel("blocks-kpi-metric-row", {
        endpoint: "/api/health/kpi",
        columns: 4,
        density: "comfortable",
        refreshInterval: 10000,
      }),

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

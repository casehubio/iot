import { page, rows, panel, table, html, lookup } from "@casehubio/pages-ui";

export function providersPage() {
  return page("Providers",
    rows(
      panel("IoT Providers", table({
        title: "Provider Status",
        sortable: true,
        lookup: lookup("providers"),
        refresh: { interval: 30000 },
      })),

      panel("Actions", html(`
        <div style="display: flex; gap: 8px; padding: 16px;">
          <button onclick="refreshProviders()">Refresh All Providers</button>
        </div>
      `)),

      panel("Bridge Connections", table({
        title: "Connected Tenancies",
        sortable: true,
        lookup: lookup("bridge-connections"),
        refresh: { interval: 30000 },
      })),
    ),
  );
}

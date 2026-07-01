import { page, rows, panel, table, selector, columns, html, lookup, groupBy, col, sortBy } from "@casehubio/pages-ui";

export function workItemsPage() {
  return page("Work Items",
    rows(
      // Status filter
      columns([2, 10],
        [selector({
          title: "Status",
          filter: { enabled: true },
          lookup: lookup("workitems", groupBy("status", col("status"))),
          subtype: "labels",
        })],
        [html("")], // spacer
      ),

      panel("Pending Tasks", table({
        title: "WorkItems",
        sortable: true,
        pageSize: 20,
        filter: { listening: true },
        lookup: lookup("workitems", sortBy("createdAt", "DESCENDING")),
        refresh: { interval: 15000 },
      })),

      panel("Actions", html(`
        <div style="display: flex; gap: 8px; padding: 16px;">
          <button onclick="claimWorkItem()">Claim</button>
          <button onclick="completeWorkItem('approve')">Approve</button>
          <button onclick="completeWorkItem('reject')">Reject</button>
        </div>
      `)),
    ),
  );
}

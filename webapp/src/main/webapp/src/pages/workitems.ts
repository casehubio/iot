import { page, rows, panel, table, split, selector, columns, hostPanel, lookup, groupBy, col, sortBy } from "@casehubio/pages-ui";

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
      ),

      // Master-detail split: table on left, work-item-detail on right
      split("horizontal",
        [
          panel("Tasks", table({
            title: "WorkItems",
            sortable: true,
            pageSize: 20,
            filter: { listening: true },
            lookup: lookup("workitems", sortBy("createdAt", "DESCENDING")),
            refresh: { interval: 15000 },
          })),
          hostPanel("blocks-work-item-detail", {
            endpoint: "/api",
          }),
        ],
        { ratio: [0.4, 0.6], minSizes: [320, 400] },
      ),
    ),
  );
}

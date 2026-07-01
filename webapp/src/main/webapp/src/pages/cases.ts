import { page, rows, panel, table, title, html, lookup, sortBy } from "@casehubio/pages-ui";
import { dataSetId, columnId } from "@casehubio/pages-data/dist/dataset/types.js";

export function casesPage() {
  return page("Cases",
    rows(
      panel("Open Cases", table({
        title: "Cases",
        sortable: true,
        pageSize: 15,
        filter: { enabled: true },
        lookup: lookup("cases", sortBy("created", "DESCENDING")),
        refresh: { interval: 30000 },
      })),

      // Case detail sub-page
      page("Case Detail",
        rows(
          title("Case Details"),

          panel("Event Log", table({
            title: "Case Timeline",
            sortable: true,
            pageSize: 10,
            lookup: lookup("case-events", sortBy("timestamp", "DESCENDING")),
          })),

          panel("Worker Results", table({
            title: "Worker Executions",
            sortable: true,
            pageSize: 5,
            lookup: lookup("case-workers"),
          })),

          panel("Actions", html(`
            <div style="display: flex; gap: 8px; padding: 16px;">
              <button onclick="approveAction()">Approve</button>
              <button onclick="rejectAction()">Reject</button>
            </div>
          `)),
        ),
        {
          dataScope: { dataset: dataSetId("cases"), idColumn: columnId("caseId") },
        },
      ),
    ),
  );
}

import { page, rows, panel, table, title, hostPanel, lookup, sortBy } from "@casehubio/pages-ui";
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

      // Case detail sub-page — tabbed via blocks-detail-pane
      page("Case Detail",
        rows(
          title("Case Details"),

          hostPanel("blocks-detail-pane", {
            selectionTopic: "case",
            tabs: [
              { id: "timeline", label: "Timeline", tagName: "iot-case-timeline-tab", order: 0 },
              { id: "suggestions", label: "Suggestions", tagName: "iot-case-suggestions-tab", order: 1 },
              { id: "actions", label: "Actions", tagName: "iot-case-actions-tab", order: 2 },
            ],
          }),
        ),
        {
          dataScope: { dataset: dataSetId("cases"), idColumn: columnId("caseId") },
        },
      ),
    ),
  );
}

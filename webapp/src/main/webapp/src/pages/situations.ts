import { page, tabs, panel, table, rows, columns, textInput, html, lookup, sortBy } from "@casehubio/pages-ui";
import { dataSetId, columnId } from "@casehubio/pages-data/dist/dataset/types.js";

export function situationsPage() {
  return page("Situations",
    tabs(
      // Active tab
      ["Active", rows(
        panel("Active Situations", table({
          title: "Active Situation Contexts",
          sortable: true,
          pageSize: 15,
          lookup: lookup("situations-active", sortBy("firstSignal", "DESCENDING")),
          refresh: { interval: 15000 },
        })),
      )],

      // Definitions tab
      ["Definitions", rows(
        panel("Situation Definitions", table({
          title: "All Situation Definitions",
          sortable: true,
          pageSize: 15,
          lookup: lookup("situation-defs"),
        })),

        // Definition editor form
        page("Edit Definition",
          rows(
            columns([6, 6],
              [textInput({ field: "situationId", label: "Situation ID" })],
              [textInput({ field: "chainMode", label: "Chain Mode" })],
            ),
            columns([6, 6],
              [textInput({ field: "eventTypes", label: "Event Types (comma-separated)" })],
              [textInput({ field: "triggerMode", label: "Trigger Mode" })],
            ),
            html(`
              <div style="display: flex; gap: 8px; padding: 16px;">
                <button onclick="saveSituationDef()">Save</button>
                <button onclick="deleteSituationDef()">Delete</button>
              </div>
            `),
          ),
          {
            dataScope: { dataset: dataSetId("situation-defs"), idColumn: columnId("situationId") },
          },
        ),
      )],
    ),
  );
}

import { page, rows, columns, hostPanel } from "@casehubio/pages-ui";
import "@casehubio/pages-aria/controller/playbook-controller.js";
import "@casehubio/pages-aria/controller/playbook-yaml-viewer.js";

export function scenariosPage() {
  return page("Scenarios",
    rows(
      columns([4, 8],
        [hostPanel("pages-playbook-controller", {
          mode: "full",
        })],
        [hostPanel("pages-playbook-yaml-viewer", {})],
      ),
    ),
  );
}

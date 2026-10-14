import { page, dockWorkbench, hostPanel, split } from "@casehubio/pages-ui";
import "@casehubio/pages-aria/controller/playbook-controller.js";
import "@casehubio/pages-aria/controller/playbook-yaml-viewer.js";

export function playbooksPage() {
  return page("Playbooks",
    dockWorkbench({
      storageKey: "playbooks",
      centre: [
        split("horizontal", [
          hostPanel("playbook-controller", { mode: "full" }),
          hostPanel("playbook-yaml-viewer"),
        ]),
      ],
      left: { zones: 1, panels: [] },
      right: { zones: 1, panels: [] },
      bottom: { zones: 1, panels: [] },
    }),
  );
}

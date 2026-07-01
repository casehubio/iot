import { page, sidebar, dataset } from "@casehubio/pages-ui";
import { loadSite } from "@casehubio/pages-runtime";
import { healthPage } from "./pages/health";
import { devicesPage } from "./pages/devices";
import { situationsPage } from "./pages/situations";
import { casesPage } from "./pages/cases";
import { workItemsPage } from "./pages/workitems";
import { auditPage } from "./pages/audit";
import { providersPage } from "./pages/providers";

// Define all datasets
dataset("devices", "/api/devices");
dataset("device-events", "sse://api/devices/stream");
dataset("device-history", "/api/devices/{deviceId}/history");
dataset("providers", "/api/providers");
dataset("situations-active", "/api/situations/active");
dataset("situation-defs", "/api/situations/definitions");
dataset("cases", "/api/cases");
dataset("case-events", "/api/cases/{caseId}/events");
dataset("case-workers", "/api/cases/{caseId}/workers");
dataset("workitems", "/api/workitems");
dataset("health", "/api/health/overview");
dataset("audit", "/api/bridge/audit");
dataset("bridge-connections", "/api/bridge/connections");

// Build the application shell
const app = page("IoT Console",
  sidebar(
    ["Health", healthPage()],
    ["Devices", devicesPage()],
    ["Situations", situationsPage()],
    ["Cases", casesPage()],
    ["Work Items", workItemsPage()],
    ["Audit", auditPage()],
    ["Providers", providersPage()],
  ),
  { settings: { mode: "dark" } },
);

// Render the application
const container = document.getElementById("app");
if (container) {
  loadSite(container, app);
}

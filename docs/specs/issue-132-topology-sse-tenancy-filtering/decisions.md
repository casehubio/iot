## D1: Filtering approach for topology SSE broadcast

**Choice:** Add tenancyId to TopologyStreamEvent, @JsonIgnore it, filter at subscription
**Alternatives:**
- Per-tenant BroadcastProcessor map — lifecycle complexity and memory leak risk for no practical benefit at expected subscriber count
- Wrapper Multi with device lookup — O(events × subscribers) registry calls, fragile with binding events that carry device sets
**Rationale:** Follows the proven pattern from DefaultIoTDeviceApi.streamDevices(). Minimal change — 3 call sites need the extra constructor arg. tenancyId is server-side only (@JsonIgnore), not serialized to frontend.
**Trade-offs:** Every TopologyStreamEvent constructor call needs tenancyId, but there are only 3 call sites.
**Sources:** DefaultIoTDeviceApi.java:129-143 (existing filter pattern), ScenarioBindingEvent.java (tenancyId on sealed interface), ARC42STORIES.MD:1480 (known concern)
**Exploration:** quick
**Status:** captured

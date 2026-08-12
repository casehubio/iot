---
title: "Wiring IoT Situations into the Platform Subscription Engine"
date: 2026-08-12
entry_type: note
subtype: diary
status: draft
projects: [casehub-iot]
tags: [subscription-engine, notifications, CDI, platform-integration]
---

# Wiring IoT Situations into the Platform Subscription Engine

The household notification worker in casehub-iot had been a stub since the webapp was first built — a function that returned mock success and a comment saying "real integration will be wired in Task 4's descriptors." That was back when the platform subscription engine didn't exist. It does now.

I'd originally planned to batch #67 with three small fixes (#87, #88, #90), but checking the code showed those were already done — landed in a previous session's S-issue batch commit, just never closed on GitHub. Verified each one against the codebase, closed all four, and moved on to the real work.

## The design review that changed the design

The initial brainstorm proposed three event categories flowing through the subscription engine: situation activations, device state changes, and case lifecycle events. Claude's decision review dismantled two of them.

Device state changes are telemetry — every light toggle, every sensor reading, every thermostat update. Pumping that volume through the notification pipeline would flood it. Situations already exist as the curated interpretation layer; that's the right abstraction for user-facing notifications. Device state changes keep their existing CDI event mechanism for in-JVM consumers.

Case lifecycle events turned out to be a platform concern, not an IoT concern. The casehub-work module already has `WorkItemLifecycleEvent` implementing `SubscribableEvent`. Building an IoT-specific version would mean every application module (clinical, AML, property management) duplicating the same plumbing. Case lifecycle subscriptions belong in the engine, not in each consuming repo.

The third revision was subtler: the original plan kept the notification worker in the case flow, turning it into a thin adapter that pushes events into the DataSource. Claude's review argued that this creates two parallel notification mechanisms — the case flow's ordered sequence and the subscription engine's asynchronous delivery. The subscription engine *is* the notification system. The case flow should model only steps requiring ordered execution: dispatch safety commands, then create the human work item. Notification fires independently at situation detection time.

## What got built

`IoTSituationEvent` in iot-api — a class implementing `SubscribableEvent` with `@JsonIgnore` on `tenancyId()` to match the platform pattern and prevent tenant IDs leaking into serialized payloads. It handles both `TRIGGERED` and `RESOLVED` change types, so subscribers can get alerts and all-clears.

`IoTSituationEventObserver` in the webapp — a CDI observer on `SituationChangeEvent` that constructs the event and pushes it into a platform-global DataSource. Best-effort delivery: failures are logged but never thrown, so safety-critical case flows can't stall because the subscription engine is temporarily down.

The three case descriptors (safety, security, HVAC anomaly) each lost their `household-notification` worker. The stub and its test were deleted.

## The spec review caught nine errors

The post-spec review found factual mistakes that would have been compilation errors: the `ChangeType` enum has `TRIGGERED`, not `ACTIVATED`. The platform's `Path.of()` takes varargs segments, not a slash-delimited string. `DataSourceDescriptor` has seven constructor fields, not three. `ObjectType.getTypeKey()` returns `Object`, not `String`. The security alert case descriptor has four workers (not three — camera-activation was missed), and the `HvacAnomalyCaseDescriptor` was missing from the cleanup list entirely.

One implementation surprise: `SituationContext.initial()` returns null for `lastTriggered()`. The observer falls back to `lastSignal()` when that happens.

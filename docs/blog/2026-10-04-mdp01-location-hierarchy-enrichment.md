---
layout: post
title: "Giving Devices a Place in the World"
date: 2026-10-04
entry_type: note
subtype: diary
projects: [casehubio/iot]
tags: [iot, homeassistant, openhab, topology, location]
---

# Giving Devices a Place in the World

`DeviceEntity.location()` has been nullable since the field was added. The TopologyAssembler parses it as a `/`-delimited path for tree rendering — `home/first-floor/bedroom` becomes three levels in the spatial view. But neither provider was populating it. OpenHAB's Thing-based resolution had it (from `thing.location()`), but the Equipment path — the one most OpenHAB users actually configure — left it null. Home Assistant had nothing at all.

The problem is that neither platform hands you hierarchical paths natively. HA gives you flat area names ("Living Room") with optional floor grouping. OpenHAB gives you semantic model groups with Location tags that nest arbitrarily. Both need construction, not extraction.

## OpenHAB: walking the semantic model

OpenHAB's semantic model is a group hierarchy: Building → Floor → Room → Equipment. Location groups contain other Location groups and Equipment groups as members. The REST API already returns this tree when you query with `tags=Location&recursive=true`.

`OpenHabLocationResolver` walks this tree recursively, accumulating path segments from each Location group's label. An equipment item three levels deep in `Home > First Floor > Bedroom` gets the path `Home/First Floor/Bedroom`. The provider fetches Location items before mapping Equipment items and passes the resolved map to the entity mapper.

## Home Assistant: joining four registries

HA is more fragmented. Area names live in the area registry. Floors live in the floor registry. The link between an entity and its area goes through the entity registry (which has an `area_id`), with a fallback through the device registry (entities inherit their device's area when they don't have one directly).

I added four DTOs and a `HomeAssistantLocationResolver` that joins these registries into a single `entity_id → path` map. Area names get slugified — "Living Room" becomes `living-room`. Floors slot in between: `first-floor/bedroom`. An optional `casehub.iot.homeassistant.location-prefix` config lets operators prepend a static hierarchy: set it to `home/ground-floor` and your kitchen entity becomes `home/ground-floor/kitchen`.

The registries are queried via REST endpoints available in HA 2024.6+. Older versions get graceful degradation — a log warning, and location stays null (same as before).

## The volatile fix

Code review caught a real race. Both entity mappers are `@ApplicationScoped` singletons. `discover()` writes `locationMap` from a worker thread; WebSocket/SSE event handlers read it from their own threads. The field was plain — not `volatile`. With `Map.copyOf()` values the worst case is stale location data on real-time events, not corruption, but it's still a data race. One-word fix in each mapper.

## What this opens up

The topology view can now render a proper spatial tree for both platforms out of the box, no manual configuration required (beyond the optional HA prefix for multi-building setups). The remaining epic item — #126, desired-state scenario delivery mode — is blocked on a `DeliveryHandler` SPI in casehub-pages, which I filed as casehubio/casehub-pages#516 today.

---
layout: post
title: "Presets are just named desired states"
date: 2026-10-04
entry_type: note
subtype: diary
projects: [casehubio/iot]
tags: [desired-state, presets, yaml, composition]
series: issue-124-saved-state-presets
---

A preset is a named desired state. That was the framing that simplified everything about this feature — the moment we stopped treating presets as a special concept and recognised them as `IoTGoals` files with a name, the design collapsed into something small.

The issue asked for five things: a preset directory, load-by-name, apply, composition via imports, and a diff/preview endpoint. I expected composition to be the hard part — presets importing other presets, conflict resolution when two presets target the same device. Instead, the interesting decision was what *not* to build.

The existing `IoTGoalLoader` already loads YAML, and `merge()` already combines fragments. The only new merge behaviour was last-wins for overlapping devices — a `mergeGoals()` method alongside the existing `merge()` that deep-merges config maps instead of rejecting duplicates. Import resolution is one-level: a preset's imports don't themselves import. This keeps the mental model flat — you can always see what a preset contains by looking at one layer.

The `import:` directive lives outside `IoTGoals` — it's a preset-layer concern. The resolver pre-parses the YAML tree, strips `import:`, and deserialises the remainder via `loadFromNode()`. An earlier approach disabled `FAIL_ON_UNKNOWN_PROPERTIES` globally on the ObjectMapper, which would have silently swallowed typos in any goal YAML across the codebase. Claude caught this in the code review — the right fix was scoping the tolerance to the resolver, not relaxing validation everywhere.

The REST surface follows the existing `@McpDomain` pattern — list, diff, apply. The diff endpoint compares a resolved preset against actual device state via `capabilities()` and returns per-device property changes. The apply endpoint compiles the preset into a `DesiredStateGraph` and reconciles through the existing pipeline: actual state, transition plan, provision.

Getting the apply endpoint wired up surfaced a pre-existing problem: the webapp's Quarkus augmentation was failing due to two upstream CDI bean conflicts — an ambiguous `GroupMembershipProvider` and an unsatisfied `EngineStrategyResolver` for `WorkStrategyContributor`. These weren't caused by the preset work but were blocking the Quarkus build entirely. A `quarkus.arc.exclude-types` entry for the two offending beans unblocked everything. The `TransitionPlanner` and `DefaultDesiredStateGraphFactory` are plain POJOs, not CDI beans — they get created inline rather than injected.

The path traversal guard on preset names was the other robustness finding — `Path.resolve()` doesn't validate containment, so `../../etc/passwd` as a preset name would escape the directory. A normalize-and-startsWith check closes that.

What this opens up: saved presets are the vocabulary for the ordering constraints work — constraints declare structural edges between devices, and presets are the unit those constraints operate on. The diff endpoint also gives the topology view something to overlay when a preset is previewed but not yet applied.

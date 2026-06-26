package io.casehub.iot.openhab;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.iot.api.DeviceCommand;
import io.casehub.iot.api.DeviceEntity;
import io.casehub.iot.api.ProviderStatus;
import io.casehub.iot.api.ProviderStatusEvent;
import io.casehub.iot.api.StateChangeEvent;
import io.casehub.iot.openhab.internal.OpenHabChannelDto;
import io.casehub.iot.openhab.internal.OpenHabItemDto;
import io.casehub.iot.openhab.internal.OpenHabSseEventDto;
import io.casehub.iot.openhab.internal.OpenHabStatePayloadDto;
import io.casehub.iot.openhab.internal.OpenHabStatusInfoDto;
import io.casehub.iot.openhab.internal.OpenHabThingDto;
import io.casehub.iot.openhab.internal.OpenHabThingTypeDto;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSE client for OpenHAB event stream with layered discovery and state caching.
 *
 * <p>Connects to OpenHAB's SSE /rest/events endpoint and maintains two device
 * cache layers:</p>
 * <ul>
 *   <li><strong>Equipment cache</strong> — keyed by Equipment Group name (semantic model)</li>
 *   <li><strong>Thing cache</strong> — keyed by Thing UID (channel metadata, unmapped Things only)</li>
 * </ul>
 *
 * <p>Individual item state changes are resolved to their parent Equipment or Thing,
 * the parent entity is reconstructed with updated member states, and a coalesced
 * {@link StateChangeEvent} is fired after a configurable window (default 50ms)
 * to batch rapid changes into a single device-level event.</p>
 *
 * <p>Thing status changes (ONLINE/OFFLINE) are also tracked: for Equipment-backed
 * Things, they override Equipment device availability; for Thing-only devices,
 * they directly update the Thing device availability.</p>
 *
 * <p>The client handles connection lifecycle including exponential backoff
 * reconnection with jitter, mirroring the pattern used by the Home
 * Assistant WebSocket client.</p>
 */
@ApplicationScoped
public class OpenHabSseClient {

    private static final Logger LOG = Logger.getLogger(OpenHabSseClient.class);

    private volatile OpenHabRestClient restClient;
    private volatile OpenHabSseRestClient sseRestClient;
    private volatile Map<String, String> categoryMapCached;

    @Inject OpenHabEntityMapper mapper;
    @Inject OpenHabConfig config;
    @Inject Event<StateChangeEvent> stateEvents;
    @Inject Event<ProviderStatusEvent> statusEvents;
    @Inject ObjectMapper objectMapper;
    @Inject @org.eclipse.microprofile.config.inject.ConfigProperty(name = "casehub.iot.tenancy-id")
    String tenancyId;
    private OpenHabThingResolver thingResolver;

    // ---- state caches ----

    /** Equipment Group item DTO — keyed by Equipment name. */
    private final ConcurrentHashMap<String, OpenHabItemDto> equipmentCache = new ConcurrentHashMap<>();

    /** Item states per Equipment — Equipment name -> (item name -> latest DTO with updated state). */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, OpenHabItemDto>> itemStateCache = new ConcurrentHashMap<>();

    /** Assembled DeviceEntity per Equipment — keyed by Equipment name. */
    private final ConcurrentHashMap<String, DeviceEntity> deviceCache = new ConcurrentHashMap<>();

    /** Reverse index: item name -> Equipment name. */
    private final ConcurrentHashMap<String, String> itemToEquipment = new ConcurrentHashMap<>();

    // ---- Thing caches (layered discovery) ----

    /** Thing-only device cache — keyed by Thing UID. */
    private final ConcurrentHashMap<String, DeviceEntity> thingDeviceCache = new ConcurrentHashMap<>();

    /** Thing DTO cache — keyed by Thing UID. */
    private final ConcurrentHashMap<String, OpenHabThingDto> thingCache = new ConcurrentHashMap<>();

    /** Thing-linked item states — Thing UID -> (item name -> latest DTO). */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, OpenHabItemDto>> thingItemStateCache = new ConcurrentHashMap<>();

    /** Reverse index: item name -> Thing UID (for Thing-only items). */
    private final ConcurrentHashMap<String, String> itemToThing = new ConcurrentHashMap<>();

    /** Forward index: Thing UID -> Equipment name (for Equipment-backed Things). */
    private final ConcurrentHashMap<String, String> thingToEquipment = new ConcurrentHashMap<>();

    /** Coalescing "before" snapshot — Equipment name -> DeviceEntity before the batch. */
    private final ConcurrentHashMap<String, DeviceEntity> coalescingBefore = new ConcurrentHashMap<>();

    /** Coalescing pending timers — Equipment name -> scheduled future. */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> coalescingTimers = new ConcurrentHashMap<>();

    // ---- connection state ----

    private volatile ProviderStatus currentStatus = ProviderStatus.DISCONNECTED;
    private volatile boolean shuttingDown = false;
    private volatile boolean firstEventReceived = false;
    private volatile io.smallrye.mutiny.subscription.Cancellable sseSubscription;
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private final ScheduledExecutorService executor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oh-sse-reconnect");
            t.setDaemon(true);
            return t;
        });

    /** CDI constructor — used by the container. */
    @Inject
    OpenHabSseClient() {
        // Injection targets set by CDI — thingResolver created lazily via init()
    }

    /**
     * CDI lifecycle init — creates non-CDI collaborators after injection.
     * Called automatically by CDI after field injection completes.
     */
    @jakarta.annotation.PostConstruct
    void postConstruct() {
        this.thingResolver = new OpenHabThingResolver(tenancyId, Map.of());
    }

    /**
     * Initializes both REST clients programmatically.
     * Called by OpenHabProvider after URL is resolved from config.
     *
     * @param url the OpenHAB base URL
     * @param authFilter the auth filter to register with both clients
     */
    void init(String url, OpenHabAuthFilter authFilter) {
        this.restClient = RestClientBuilder.newBuilder()
            .baseUri(URI.create(url))
            .register(authFilter)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build(OpenHabRestClient.class);

        this.sseRestClient = RestClientBuilder.newBuilder()
            .baseUri(URI.create(url))
            .register(authFilter)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build(OpenHabSseRestClient.class);
    }

    /** Test constructor — bypasses CDI for unit testing cache and resolution logic. */
    OpenHabSseClient(OpenHabEntityMapper mapper, ObjectMapper objectMapper, OpenHabThingResolver thingResolver) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.thingResolver = thingResolver;
    }

    // ---- public API ----

    /**
     * Connects to OpenHAB: discovers Equipment and Things via REST, then subscribes to SSE events.
     *
     * <p>Layered discovery pipeline:</p>
     * <ol>
     *   <li>Phase 1 — Equipment mapping (existing semantic model)</li>
     *   <li>Phase 2 — Build Thing indexes + enhance Equipment availability from Thing status</li>
     *   <li>Phase 3 — Thing mapping for unmapped Things (when enabled)</li>
     *   <li>Phase 4 — Fetch item states for unmapped Things</li>
     * </ol>
     *
     * @return completes when the initial REST discovery finishes; SSE subscription is fire-and-forget
     */
    public Uni<Void> connect() {
        io.smallrye.mutiny.subscription.Cancellable old = sseSubscription;
        if (old != null) {
            old.cancel();
        }
        sseSubscription = null;

        fireStatus(ProviderStatus.CONNECTING);
        firstEventReceived = false;

        return Uni.combine().all().unis(
            restClient.getItems("Equipment", true),
            restClient.getThings()
                .onFailure().recoverWithItem(List.of()),
            categoryMapCached != null
                ? Uni.createFrom().item(List.<OpenHabThingTypeDto>of())
                : restClient.getThingTypes().onFailure().recoverWithItem(List.of())
        ).asTuple()
        .chain(tuple -> {
            List<OpenHabItemDto> equipments = tuple.getItem1();
            List<OpenHabThingDto> things = tuple.getItem2();
            List<OpenHabThingTypeDto> thingTypes = tuple.getItem3();

            if (categoryMapCached == null && !thingTypes.isEmpty()) {
                Map<String, String> map = new HashMap<>();
                for (OpenHabThingTypeDto tt : thingTypes) {
                    if (tt.category() != null && !tt.category().isBlank()) {
                        map.put(tt.uid(), tt.category());
                    }
                }
                categoryMapCached = Map.copyOf(map);
                thingResolver = new OpenHabThingResolver(tenancyId, categoryMapCached);
            }

            // Phase 1: Equipment mapping (existing)
            populateCaches(equipments);
            Set<String> coveredItems = buildCoveredItemsSet(equipments);

            // Phase 2: Build indexes + enhance availability (always)
            buildThingIndexes(things, coveredItems);
            enhanceAvailabilityFromThings(things, coveredItems);

            // Phase 3: Thing mapping (only when enabled)
            boolean thingDiscovery = config != null && config.thingDiscoveryEnabled();
            if (!thingDiscovery) {
                return Uni.createFrom().voidItem();
            }

            List<OpenHabThingDto> unmappedThings = filterUnmapped(things, coveredItems);
            if (unmappedThings.isEmpty()) {
                return Uni.createFrom().voidItem();
            }

            // Phase 4: Fetch item states for unmapped Things
            return restClient.getAllItems()
                .onFailure().recoverWithItem(List.of())
                .invoke(allItems -> {
                    Map<String, OpenHabItemDto> itemLookup = buildFilteredItemLookup(allItems, unmappedThings);
                    populateThingCaches(unmappedThings, itemLookup);
                });
        })
        .invoke(v -> subscribeSse())
        .replaceWithVoid()
        .onFailure().invoke(e -> {
            LOG.warnf(e, "OpenHAB discovery failed");
            fireStatus(ProviderStatus.DISCONNECTED);
            scheduleReconnect();
        });
    }

    public ProviderStatus currentStatus() {
        return currentStatus;
    }

    /**
     * Returns a defensive copy of all currently cached devices
     * (Equipment-based and Thing-based combined).
     */
    public List<DeviceEntity> cachedDevices() {
        var all = new ArrayList<>(deviceCache.values());
        all.addAll(thingDeviceCache.values());
        return List.copyOf(all);
    }

    /**
     * Resolves the target item name for command dispatch.
     *
     * <p>Checks two layers:</p>
     * <ol>
     *   <li><strong>Equipment path</strong> — uses semantic tags to find the matching member</li>
     *   <li><strong>Thing path</strong> — uses channel itemType metadata to find the matching
     *       linked item via {@link #resolveByChannelType}</li>
     * </ol>
     *
     * @param command the device command to resolve
     * @return the OpenHAB item name to send the command to, or null if unresolved
     */
    public String resolveTargetItem(DeviceCommand command) {
        String deviceId = command.targetDeviceId();

        // Equipment path (existing)
        if (equipmentCache.containsKey(deviceId)) {
            ConcurrentHashMap<String, OpenHabItemDto> members = itemStateCache.get(deviceId);
            if (members == null || members.isEmpty()) {
                return null;
            }

            // Special case: set_position tries Status+OpenState first, then Control+OpenState
            if (DeviceCommand.ACTION_SET_POSITION.equals(command.action())) {
                String result = findMemberWithTags(members, Set.of("Status", "OpenState"));
                if (result != null) {
                    return result;
                }
                return findMemberWithTags(members, Set.of("Control", "OpenState"));
            }

            Set<String> requiredTags = resolveRequiredTags(command);
            if (requiredTags == null) {
                return null;
            }

            return findMemberWithTags(members, requiredTags);
        }

        // Thing path (new)
        OpenHabThingDto thing = thingCache.get(deviceId);
        if (thing != null) {
            return resolveByChannelType(command, thing);
        }

        return null;
    }

    /**
     * Resolves the target item for a Thing-scoped device command using channel metadata.
     *
     * <p>Maps command actions to channel itemType, filtering STATE channels only.
     * For turn_on/turn_off, prefers Color &gt; Dimmer &gt; Switch. For set_temperature,
     * uses setpoint disambiguation (channelTypeUID or id contains "setpoint", "target",
     * or "desired"). For set_volume, prefers channels with "volume" in channelTypeUID.</p>
     *
     * @param command the device command
     * @param thing   the Thing DTO from cache
     * @return the linked item name for the matching channel, or null if no match
     */
    String resolveByChannelType(DeviceCommand command, OpenHabThingDto thing) {
        List<OpenHabChannelDto> channels = thing.stateChannels();

        return switch (command.action()) {
            case DeviceCommand.ACTION_TURN_ON, DeviceCommand.ACTION_TURN_OFF ->
                resolveOnOffChannel(channels);
            case DeviceCommand.ACTION_SET_TEMPERATURE ->
                resolveSetpointChannel(channels);
            case DeviceCommand.ACTION_LOCK, DeviceCommand.ACTION_UNLOCK ->
                findFirstLinkedItem(channels, "Switch");
            case DeviceCommand.ACTION_SET_POSITION ->
                findFirstLinkedItem(channels, "Rollershutter");
            case DeviceCommand.ACTION_SET_VOLUME ->
                resolveVolumeChannel(channels);
            default -> null;
        };
    }

    /**
     * For turn_on/turn_off: prefers Color > Dimmer > Switch (all support ON/OFF in OpenHAB).
     */
    private String resolveOnOffChannel(List<OpenHabChannelDto> channels) {
        String colorItem = null;
        String dimmerItem = null;
        String switchItem = null;

        for (OpenHabChannelDto ch : channels) {
            String itemType = ch.itemType();
            if (itemType == null || ch.linkedItems() == null || ch.linkedItems().isEmpty()) continue;

            switch (itemType) {
                case "Color" -> { if (colorItem == null) colorItem = ch.linkedItems().get(0); }
                case "Dimmer" -> { if (dimmerItem == null) dimmerItem = ch.linkedItems().get(0); }
                case "Switch" -> { if (switchItem == null) switchItem = ch.linkedItems().get(0); }
            }
        }

        if (colorItem != null) return colorItem;
        if (dimmerItem != null) return dimmerItem;
        return switchItem;
    }

    private String resolveSetpointChannel(List<OpenHabChannelDto> channels) {
        for (OpenHabChannelDto ch : channels) {
            if (!"Number:Temperature".equals(ch.itemType())) continue;
            if (ch.linkedItems() == null || ch.linkedItems().isEmpty()) continue;
            if (ch.isSetpointChannel()) {
                return ch.linkedItems().get(0);
            }
        }
        return null;
    }

    /**
     * For set_volume: prefers Dimmer channel with "volume" in channelTypeUID, falls back to any Dimmer.
     */
    private String resolveVolumeChannel(List<OpenHabChannelDto> channels) {
        String volumeDimmer = null;
        String anyDimmer = null;

        for (OpenHabChannelDto ch : channels) {
            if (!"Dimmer".equals(ch.itemType())) continue;
            if (ch.linkedItems() == null || ch.linkedItems().isEmpty()) continue;

            if (anyDimmer == null) {
                anyDimmer = ch.linkedItems().get(0);
            }
            if (ch.channelTypeUID() != null
                    && ch.channelTypeUID().toLowerCase(java.util.Locale.ROOT).contains("volume")) {
                volumeDimmer = ch.linkedItems().get(0);
            }
        }

        return volumeDimmer != null ? volumeDimmer : anyDimmer;
    }

    /**
     * Finds the first linked item from a STATE channel matching the given itemType.
     */
    private String findFirstLinkedItem(List<OpenHabChannelDto> channels, String targetItemType) {
        for (OpenHabChannelDto ch : channels) {
            if (targetItemType.equals(ch.itemType())
                    && ch.linkedItems() != null && !ch.linkedItems().isEmpty()) {
                return ch.linkedItems().get(0);
            }
        }
        return null;
    }

    private String findMemberWithTags(ConcurrentHashMap<String, OpenHabItemDto> members, Set<String> requiredTags) {
        for (OpenHabItemDto member : members.values()) {
            Set<String> memberTags = member.tagSet();
            if (memberTags.containsAll(requiredTags)) {
                return member.name();
            }
        }
        return null;
    }

    // ---- cache population (package-private for testing) ----

    /**
     * Populates all caches from a discovery response.
     *
     * <p>Called on initial connect and on reconnect (re-discovers to refresh indexes).</p>
     */
    void populateCaches(List<OpenHabItemDto> equipments) {
        // Clear stale data
        equipmentCache.clear();
        itemStateCache.clear();
        deviceCache.clear();
        itemToEquipment.clear();

        for (OpenHabItemDto equipment : equipments) {
            equipmentCache.put(equipment.name(), equipment);

            ConcurrentHashMap<String, OpenHabItemDto> memberMap = new ConcurrentHashMap<>();
            if (equipment.members() != null) {
                for (OpenHabItemDto member : equipment.members()) {
                    memberMap.put(member.name(), member);
                    itemToEquipment.put(member.name(), equipment.name());
                }
            }
            itemStateCache.put(equipment.name(), memberMap);

            DeviceEntity entity = mapper.mapEquipment(equipment, Instant.now());
            if (entity != null) {
                deviceCache.put(equipment.name(), entity);
            }
        }

        LOG.infof("OpenHAB caches populated: %d equipments, %d items indexed",
            equipmentCache.size(), itemToEquipment.size());
    }

    // ---- Thing cache population (package-private for testing) ----

    /**
     * Collects all member item names from Equipment Groups into a Set.
     */
    Set<String> buildCoveredItemsSet(List<OpenHabItemDto> equipments) {
        Set<String> covered = new HashSet<>();
        for (OpenHabItemDto eq : equipments) {
            if (eq.members() != null) {
                for (OpenHabItemDto member : eq.members()) {
                    covered.add(member.name());
                }
            }
        }
        return covered;
    }

    /**
     * Builds reverse indexes: {@code thingToEquipment} (Thing UID → Equipment name)
     * for Equipment-backed Things, by matching Thing channel linked items to covered items.
     */
    void buildThingIndexes(List<OpenHabThingDto> things, Set<String> coveredItems) {
        thingToEquipment.clear();
        for (OpenHabThingDto thing : things) {
            if (thing.channels() == null) continue;
            for (OpenHabChannelDto ch : thing.channels()) {
                if (ch.linkedItems() == null) continue;
                for (String linkedItem : ch.linkedItems()) {
                    String equipmentName = itemToEquipment.get(linkedItem);
                    if (equipmentName != null) {
                        thingToEquipment.put(thing.uid(), equipmentName);
                        break;
                    }
                }
                if (thingToEquipment.containsKey(thing.uid())) break;
            }
        }
    }

    /**
     * For Equipment-backed Things: if Thing is OFFLINE, rebuild the Equipment device
     * with available=false and update deviceCache.
     */
    void enhanceAvailabilityFromThings(List<OpenHabThingDto> things, Set<String> coveredItems) {
        for (OpenHabThingDto thing : things) {
            String equipmentName = thingToEquipment.get(thing.uid());
            if (equipmentName == null) continue;
            if (!thing.isOnline()) {
                updateEquipmentAvailability(equipmentName, false);
            }
        }
    }

    /**
     * Returns Things where NO linked item is in coveredItems.
     */
    List<OpenHabThingDto> filterUnmapped(List<OpenHabThingDto> things, Set<String> coveredItems) {
        List<OpenHabThingDto> unmapped = new ArrayList<>();
        for (OpenHabThingDto thing : things) {
            boolean hasOverlap = false;
            if (thing.channels() != null) {
                for (OpenHabChannelDto ch : thing.channels()) {
                    if (ch.linkedItems() != null) {
                        for (String linked : ch.linkedItems()) {
                            if (coveredItems.contains(linked)) {
                                hasOverlap = true;
                                break;
                            }
                        }
                    }
                    if (hasOverlap) break;
                }
            }
            if (!hasOverlap) {
                unmapped.add(thing);
            }
        }
        return unmapped;
    }

    /**
     * Builds a name→item lookup containing only items linked to unmapped Thing channels.
     */
    Map<String, OpenHabItemDto> buildFilteredItemLookup(List<OpenHabItemDto> allItems,
                                                         List<OpenHabThingDto> unmappedThings) {
        Set<String> neededItems = new HashSet<>();
        for (OpenHabThingDto thing : unmappedThings) {
            if (thing.channels() == null) continue;
            for (OpenHabChannelDto ch : thing.channels()) {
                if (ch.linkedItems() != null) {
                    neededItems.addAll(ch.linkedItems());
                }
            }
        }

        Map<String, OpenHabItemDto> lookup = new HashMap<>();
        for (OpenHabItemDto item : allItems) {
            if (neededItems.contains(item.name())) {
                lookup.put(item.name(), item);
            }
        }
        return lookup;
    }

    /**
     * Populates Thing caches for unmapped Things: resolves each via ThingResolver,
     * builds via DeviceBuilder, and updates thingCache/thingDeviceCache/thingItemStateCache/itemToThing.
     */
    void populateThingCaches(List<OpenHabThingDto> unmappedThings,
                              Map<String, OpenHabItemDto> itemLookup) {
        thingDeviceCache.clear();
        thingCache.clear();
        thingItemStateCache.clear();
        itemToThing.clear();

        for (OpenHabThingDto thing : unmappedThings) {
            // Build per-thing item states from its channels
            Map<String, OpenHabItemDto> thingItemStates = new HashMap<>();
            ConcurrentHashMap<String, OpenHabItemDto> thingItemStateCacheEntry = new ConcurrentHashMap<>();

            if (thing.channels() != null) {
                for (OpenHabChannelDto ch : thing.channels()) {
                    if (ch.linkedItems() != null) {
                        for (String linkedItem : ch.linkedItems()) {
                            OpenHabItemDto item = itemLookup.get(linkedItem);
                            if (item != null) {
                                thingItemStates.put(linkedItem, item);
                                thingItemStateCacheEntry.put(linkedItem, item);
                                itemToThing.put(linkedItem, thing.uid());
                            }
                        }
                    }
                }
            }

            ResolvedDeviceFields fields = thingResolver.resolve(thing, thingItemStates, Instant.now());
            if (fields == null) continue;

            DeviceEntity entity = OpenHabDeviceBuilder.build(fields);
            thingCache.put(thing.uid(), thing);
            thingDeviceCache.put(thing.uid(), entity);
            thingItemStateCache.put(thing.uid(), thingItemStateCacheEntry);
        }

        LOG.infof("Thing caches populated: %d thing-only devices, %d thing-linked items",
            thingDeviceCache.size(), itemToThing.size());
    }

    // ---- SSE event handling (package-private for testing) ----

    /**
     * Handles a raw SSE event string.
     *
     * <p>Parses the event, resolves the item to its parent Equipment, updates the
     * item state cache, reconstructs the Equipment DTO, re-maps to DeviceEntity,
     * and triggers coalesced state change event emission.</p>
     */
    void handleSseEvent(String eventData) {
        try {
            OpenHabSseEventDto sseEvent = objectMapper.readValue(eventData, OpenHabSseEventDto.class);

            // Handle Thing status changes
            if ("ThingStatusInfoChangedEvent".equals(sseEvent.type())) {
                handleThingStatusEvent(sseEvent);
                return;
            }

            if (!"ItemStateChangedEvent".equals(sseEvent.type())) {
                return;
            }

            // Extract item name from topic: "openhab/items/{itemName}/statechanged"
            String[] topicParts = sseEvent.topic().split("/");
            if (topicParts.length < 3) {
                LOG.warnf("Unexpected SSE topic format: %s", sseEvent.topic());
                return;
            }
            String itemName = topicParts[2];

            // Look up parent Equipment (Equipment path has priority over Thing path)
            String equipmentName = itemToEquipment.get(itemName);
            if (equipmentName != null) {
                handleEquipmentItemStateChange(itemName, equipmentName, sseEvent);
                return;
            }

            // Check Thing-linked item
            String thingUid = itemToThing.get(itemName);
            if (thingUid != null) {
                OpenHabStatePayloadDto payload = objectMapper.readValue(
                    sseEvent.payload(), OpenHabStatePayloadDto.class);
                handleThingItemStateChange(itemName, thingUid, payload.value());
                return;
            }

            LOG.debugf("SSE event for item %s not in any Equipment or Thing — ignoring", itemName);

        } catch (Exception e) {
            LOG.warnf(e, "Failed to process SSE event — ignoring");
        }
    }

    /**
     * Handles an item state change for an Equipment-backed item.
     */
    private void handleEquipmentItemStateChange(String itemName, String equipmentName,
                                                 OpenHabSseEventDto sseEvent) throws Exception {
        OpenHabStatePayloadDto payload = objectMapper.readValue(
            sseEvent.payload(), OpenHabStatePayloadDto.class);

        ConcurrentHashMap<String, OpenHabItemDto> members = itemStateCache.get(equipmentName);
        if (members == null) return;

        OpenHabItemDto existingItem = members.get(itemName);
        if (existingItem == null) return;

        // Create new record with updated state
        OpenHabItemDto updatedItem = new OpenHabItemDto(
            existingItem.type(), existingItem.name(), existingItem.label(),
            payload.value(), existingItem.tags(), existingItem.members(),
            existingItem.stateDescription());

        members.put(itemName, updatedItem);

        // Reconstruct Equipment DTO with updated members
        OpenHabItemDto equip = equipmentCache.get(equipmentName);
        if (equip == null) return;

        OpenHabItemDto updatedEquipment = new OpenHabItemDto(
            equip.type(), equip.name(), equip.label(), equip.state(), equip.tags(),
            List.copyOf(members.values()),
            equip.stateDescription());

        DeviceEntity newEntity = mapper.mapEquipment(updatedEquipment, Instant.now());
        if (newEntity == null) return;

        DeviceEntity oldEntity = deviceCache.put(equipmentName, newEntity);

        coalescingTimers.computeIfAbsent(equipmentName, name -> {
            coalescingBefore.putIfAbsent(name, oldEntity);
            return scheduleCoalesced(name);
        });
    }

    /**
     * Handles a Thing status change SSE event.
     */
    private void handleThingStatusEvent(OpenHabSseEventDto sseEvent) {
        try {
            // Parse Thing UID from topic: "openhab/things/{thingUID}/statuschanged"
            String[] parts = sseEvent.topic().split("/");
            if (parts.length < 3) return;
            String thingUid = parts[2];

            // Parse payload as [newStatus, oldStatus] array
            List<OpenHabStatusInfoDto> statuses = objectMapper.readValue(
                sseEvent.payload(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, OpenHabStatusInfoDto.class));
            if (statuses.isEmpty()) return;
            boolean online = "ONLINE".equals(statuses.get(0).status());

            // Check if this Thing backs an Equipment device
            String equipmentName = thingToEquipment.get(thingUid);
            if (equipmentName != null) {
                updateEquipmentAvailability(equipmentName, online);
                return;
            }

            // Check if this is a Thing-only device
            DeviceEntity existing = thingDeviceCache.get(thingUid);
            if (existing != null) {
                updateThingDeviceAvailability(thingUid, online);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to process Thing status event — ignoring");
        }
    }

    /**
     * Handles an item state change for a Thing-linked item.
     * Re-resolves the Thing device from updated item states and updates thingDeviceCache.
     */
    private void handleThingItemStateChange(String itemName, String thingUid, String newState) {
        ConcurrentHashMap<String, OpenHabItemDto> members = thingItemStateCache.get(thingUid);
        if (members == null) return;

        OpenHabItemDto existingItem = members.get(itemName);
        if (existingItem == null) return;

        // Create new record with updated state
        OpenHabItemDto updatedItem = new OpenHabItemDto(
            existingItem.type(), existingItem.name(), existingItem.label(),
            newState, existingItem.tags(), existingItem.members(),
            existingItem.stateDescription());
        members.put(itemName, updatedItem);

        // Re-resolve Thing device
        OpenHabThingDto thing = thingCache.get(thingUid);
        if (thing == null) return;

        ResolvedDeviceFields fields = thingResolver.resolve(thing, new HashMap<>(members), Instant.now());
        if (fields == null) return;

        DeviceEntity newEntity = OpenHabDeviceBuilder.build(fields);
        DeviceEntity oldEntity = thingDeviceCache.put(thingUid, newEntity);

        // Coalescing for Thing devices uses the thingUid as key
        coalescingTimers.computeIfAbsent(thingUid, uid -> {
            coalescingBefore.putIfAbsent(uid, oldEntity);
            return scheduleCoalesced(uid);
        });
    }

    // ---- availability updates ----

    /**
     * Rebuilds an Equipment device with updated availability and stores in deviceCache.
     */
    private void updateEquipmentAvailability(String equipmentName, boolean online) {
        DeviceEntity existing = deviceCache.get(equipmentName);
        if (existing == null) return;

        OpenHabItemDto equip = equipmentCache.get(equipmentName);
        if (equip == null) return;

        ConcurrentHashMap<String, OpenHabItemDto> members = itemStateCache.get(equipmentName);
        if (members == null) return;

        // Rebuild Equipment DTO with current members
        OpenHabItemDto updatedEquipment = new OpenHabItemDto(
            equip.type(), equip.name(), equip.label(), equip.state(), equip.tags(),
            List.copyOf(members.values()),
            equip.stateDescription());

        // Re-resolve fields to get base, then override availability
        ResolvedDeviceFields baseFields = mapper.resolveFromEquipment(updatedEquipment, Instant.now());
        if (baseFields == null) return;

        DeviceEntity newEntity = OpenHabDeviceBuilder.build(baseFields.withAvailable(online));
        deviceCache.put(equipmentName, newEntity);
    }

    /**
     * Rebuilds a Thing-only device with updated availability and stores in thingDeviceCache.
     */
    private void updateThingDeviceAvailability(String thingUid, boolean online) {
        OpenHabThingDto thing = thingCache.get(thingUid);
        if (thing == null) return;

        ConcurrentHashMap<String, OpenHabItemDto> members = thingItemStateCache.get(thingUid);
        if (members == null) return;

        // Create a modified Thing DTO with updated status
        OpenHabThingDto updatedThing = new OpenHabThingDto(
            thing.uid(), thing.label(), thing.thingTypeUID(),
            new OpenHabStatusInfoDto(online ? "ONLINE" : "OFFLINE", null),
            thing.channels(), thing.location());

        ResolvedDeviceFields fields = thingResolver.resolve(updatedThing, new HashMap<>(members), Instant.now());
        if (fields == null) return;

        DeviceEntity newEntity = OpenHabDeviceBuilder.build(fields);
        thingDeviceCache.put(thingUid, newEntity);
    }

    // ---- test accessors (package-private) ----

    String getItemToEquipment(String itemName) {
        return itemToEquipment.get(itemName);
    }

    String getItemToThing(String itemName) {
        return itemToThing.get(itemName);
    }

    String getThingToEquipment(String thingUid) {
        return thingToEquipment.get(thingUid);
    }

    DeviceEntity getThingDevice(String thingUid) {
        return thingDeviceCache.get(thingUid);
    }

    // ---- SSE subscription ----

    private void subscribeSse() {
        sseSubscription = sseRestClient.subscribeEvents("openhab/items/*/statechanged,openhab/things/*/statuschanged")
            .subscribe().with(
                event -> {
                    if (!firstEventReceived) {
                        firstEventReceived = true;
                        fireStatus(ProviderStatus.CONNECTED);
                        reconnectAttempt.set(0);
                    }
                    handleSseEvent(event.data());
                },
                error -> {
                    if (!shuttingDown) {
                        LOG.warnf(error, "OpenHAB SSE stream error");
                        fireStatus(ProviderStatus.DISCONNECTED);
                        scheduleReconnect();
                    }
                },
                () -> {
                    if (!shuttingDown) {
                        LOG.info("OpenHAB SSE stream completed");
                        fireStatus(ProviderStatus.DISCONNECTED);
                        scheduleReconnect();
                    }
                }
            );
    }

    // ---- coalescing ----

    private ScheduledFuture<?> scheduleCoalesced(String equipmentName) {
        int windowMs = config != null ? config.coalesceWindowMs() : 50;
        return executor.schedule(() -> fireCoalesced(equipmentName), windowMs, TimeUnit.MILLISECONDS);
    }

    private void fireCoalesced(String deviceKey) {
        coalescingTimers.remove(deviceKey);  // Remove timer first — concurrent events now start fresh cycle
        DeviceEntity before = coalescingBefore.remove(deviceKey);
        DeviceEntity after = deviceCache.get(deviceKey);
        if (after == null) {
            after = thingDeviceCache.get(deviceKey);
        }

        if (before != null && after != null) {
            try {
                Set<String> changedCapabilities = StateChangeEvent.deriveChangedCapabilities(before, after);
                if (!changedCapabilities.isEmpty()) {
                    stateEvents.fireAsync(new StateChangeEvent(
                        before, after, changedCapabilities, Instant.now(), "openhab"));
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to fire coalesced state change for %s", deviceKey);
            }
        }
    }

    // ---- reconnection ----

    void scheduleReconnect() {
        if (shuttingDown) return;
        int attempt = reconnectAttempt.getAndIncrement();
        double base = reconnectBaseSeconds() * Math.pow(2, attempt);
        double capped = Math.min(base, reconnectMaxSeconds());
        double jittered = capped * (0.75 + 0.5 * ThreadLocalRandom.current().nextDouble());
        LOG.infof("Scheduling OpenHAB reconnect attempt %d in %.1fs", attempt + 1, jittered);
        executor.schedule(() -> connect().subscribe().with(
            v -> {},
            e -> {
                LOG.warnf(e, "OpenHAB reconnect attempt %d failed", attempt + 1);
                scheduleReconnect();
            }
        ), (long) (jittered * 1000), TimeUnit.MILLISECONDS);
    }

    private int reconnectBaseSeconds() {
        return config != null ? config.reconnectBaseSeconds() : 5;
    }

    private int reconnectMaxSeconds() {
        return config != null ? config.reconnectMaxSeconds() : 300;
    }

    // ---- status ----

    private void fireStatus(ProviderStatus newStatus) {
        ProviderStatus oldStatus = currentStatus;
        currentStatus = newStatus;
        if (statusEvents != null) {
            statusEvents.fireAsync(new ProviderStatusEvent("openhab", oldStatus, newStatus));
        }
    }

    // ---- target item resolution helpers ----

    private Set<String> resolveRequiredTags(DeviceCommand command) {
        return switch (command.action()) {
            case DeviceCommand.ACTION_TURN_ON, DeviceCommand.ACTION_TURN_OFF ->
                Set.of("Control", "Switch");
            case DeviceCommand.ACTION_SET_TEMPERATURE ->
                Set.of("Setpoint", "Temperature");
            case DeviceCommand.ACTION_LOCK, DeviceCommand.ACTION_UNLOCK ->
                Set.of("Control", "Switch");
            case DeviceCommand.ACTION_SET_POSITION ->
                null; // handled by OR logic in resolveTargetItem
            case DeviceCommand.ACTION_SET_VOLUME ->
                Set.of("Control", "SoundVolume");
            default -> {
                LOG.warnf("Unknown command action for item resolution: %s", command.action());
                yield null;
            }
        };
    }

    // ---- shutdown ----

    @PreDestroy
    public void stop() {
        shuttingDown = true;

        // Cancel SSE subscription
        io.smallrye.mutiny.subscription.Cancellable sub = sseSubscription;
        if (sub != null) {
            sub.cancel();
        }

        // Cancel all coalescing timers
        coalescingTimers.values().forEach(f -> f.cancel(false));
        coalescingTimers.clear();

        // Clear Thing caches
        thingDeviceCache.clear();
        thingCache.clear();
        thingItemStateCache.clear();
        itemToThing.clear();
        thingToEquipment.clear();

        executor.shutdownNow();
    }
}

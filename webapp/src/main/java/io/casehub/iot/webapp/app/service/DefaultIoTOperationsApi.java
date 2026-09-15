package io.casehub.iot.webapp.app.service;

import io.casehub.iot.api.bridge.BridgeAuditEventType;
import io.casehub.iot.api.bridge.BridgeAuditQuery;
import io.casehub.iot.api.bridge.BridgeAuditStore;
import io.casehub.iot.api.spi.DeviceProvider;
import io.casehub.iot.api.spi.DeviceRegistry;
import io.casehub.iot.bridge.server.BridgeConnectionRegistry;
import io.casehub.iot.webapp.rest.HealthOverviewResponse;
import io.casehub.iot.webapp.spi.IoTOperationsApi;
import io.casehub.iot.webapp.view.AuditTrailView;
import io.casehub.iot.webapp.view.BridgeConnectionsView;
import io.casehub.iot.webapp.view.ProviderStatusView;
import io.casehub.iot.webapp.view.RefreshResultView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class DefaultIoTOperationsApi implements IoTOperationsApi {

    @Inject Instance<DeviceProvider> providers;
    @Inject DeviceRegistry deviceRegistry;
    @Inject BridgeConnectionRegistry connectionRegistry;
    @Inject BridgeAuditStore auditStore;

    @Override
    public List<ProviderStatusView> listProviders(String tenancyId) {
        return providers.stream()
                .map(p -> {
                    var deviceCount = (int) deviceRegistry.findAll().stream()
                            .filter(d -> d.providerId().equals(p.providerId()))
                            .filter(d -> d.tenancyId().equals(tenancyId))
                            .count();
                    return new ProviderStatusView(p.providerId(), p.status().name(), deviceCount);
                })
                .toList();
    }

    @Override
    public ProviderStatusView getProvider(String providerId, String tenancyId) {
        var provider = providers.stream()
                .filter(p -> p.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Provider not found: " + providerId));
        var deviceCount = (int) deviceRegistry.findAll().stream()
                .filter(d -> d.providerId().equals(provider.providerId()))
                .filter(d -> d.tenancyId().equals(tenancyId))
                .count();
        return new ProviderStatusView(provider.providerId(), provider.status().name(), deviceCount);
    }

    @Override
    public RefreshResultView refreshAllProviders(String tenancyId) {
        deviceRegistry.refresh();
        return new RefreshResultView("Device discovery triggered for all providers");
    }

    @Override
    public RefreshResultView refreshProvider(String providerId, String tenancyId) {
        try {
            deviceRegistry.refresh(providerId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }
        return new RefreshResultView("Device discovery triggered for provider: " + providerId);
    }

    @Override
    public BridgeConnectionsView getBridgeConnections(String tenancyId) {
        var tenancies = connectionRegistry.connectedTenancies().stream()
                .map(t -> new BridgeConnectionsView.TenancyConnection(t, null))
                .toList();
        return new BridgeConnectionsView(connectionRegistry.hasAnyConnection(), tenancies);
    }

    @Override
    public AuditTrailView getBridgeAudit(String eventType, String deviceId, String correlationId,
                                          Instant from, Instant to, Integer offset, Integer limit,
                                          String tenancyId) {
        var queryBuilder = BridgeAuditQuery.builder().tenancyId(tenancyId);
        if (eventType != null) {
            queryBuilder.eventType(BridgeAuditEventType.valueOf(eventType.toUpperCase()));
        }
        if (deviceId != null) queryBuilder.deviceId(deviceId);
        if (correlationId != null) queryBuilder.correlationId(correlationId);
        if (from != null) queryBuilder.from(from);
        if (to != null) queryBuilder.to(to);
        int effectiveOffset = offset != null ? offset : 0;
        int effectiveLimit = limit != null ? Math.min(limit, 500) : 100;
        queryBuilder.offset(effectiveOffset).limit(effectiveLimit);

        var events = auditStore.query(queryBuilder.build());
        var records = events.stream()
                .map(r -> new AuditTrailView.AuditRecord(
                        r.eventType().name(), r.deviceId(), r.correlationId(),
                        r.message(), r.receivedAt()))
                .toList();
        return new AuditTrailView(records, events.size(), effectiveOffset, effectiveLimit);
    }

    @Override
    public HealthOverviewResponse getHealthOverview(String tenancyId) {
        var providerStatuses = providers.stream()
                .map(p -> {
                    var deviceCount = (int) deviceRegistry.findAll().stream()
                            .filter(d -> d.providerId().equals(p.providerId()))
                            .filter(d -> d.tenancyId().equals(tenancyId))
                            .count();
                    return new HealthOverviewResponse.ProviderStatus(
                            p.providerId(), p.status().name(), deviceCount);
                })
                .toList();
        var bridgeConnections = connectionRegistry.connectedTenancies().stream()
                .map(t -> new HealthOverviewResponse.BridgeConnection(t, Instant.now().toString()))
                .toList();
        return new HealthOverviewResponse(providerStatuses, bridgeConnections, 0, 0, 0);
    }
}

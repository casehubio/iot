package io.casehub.iot.webapp.app.cbr;

import io.casehub.neocortex.memory.MemoryDomain;
import io.casehub.neocortex.memory.cbr.CbrCaseMemoryStore;
import io.casehub.neocortex.memory.cbr.CbrRetentionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CbrRetentionJobTest {

    private CbrCaseMemoryStore store;
    private CbrRetentionConfig config;

    private static final String TENANT_ID = "test-tenant";

    @BeforeEach
    void setUp() {
        store = mock(CbrCaseMemoryStore.class);
        config = mock(CbrRetentionConfig.class);
        when(config.maxAgeDays()).thenReturn(Optional.empty());
        when(config.maxCasesPerType()).thenReturn(Optional.empty());
    }

    @Test
    void purge_skipsWhenNeitherThresholdConfigured() {
        var job = new CbrRetentionJob(store, config, TENANT_ID);
        job.purge();
        verify(store, never()).purge(any());
    }

    @Test
    void purge_callsStoreWithMaxAgeDaysOnly() {
        when(config.maxAgeDays()).thenReturn(Optional.of(90));
        when(store.purge(any())).thenReturn(5);

        var job = new CbrRetentionJob(store, config, TENANT_ID);
        job.purge();

        var captor = ArgumentCaptor.forClass(CbrRetentionPolicy.class);
        verify(store).purge(captor.capture());

        CbrRetentionPolicy policy = captor.getValue();
        assertThat(policy.tenantId()).isEqualTo(TENANT_ID);
        assertThat(policy.domain()).isEqualTo(new MemoryDomain("iot"));
        assertThat(policy.caseType()).isNull();
        assertThat(policy.maxAgeDays()).isEqualTo(90);
        assertThat(policy.maxCasesPerType()).isNull();
    }

    @Test
    void purge_callsStoreWithMaxCasesPerTypeOnly() {
        when(config.maxCasesPerType()).thenReturn(Optional.of(500));
        when(store.purge(any())).thenReturn(3);

        var job = new CbrRetentionJob(store, config, TENANT_ID);
        job.purge();

        var captor = ArgumentCaptor.forClass(CbrRetentionPolicy.class);
        verify(store).purge(captor.capture());

        CbrRetentionPolicy policy = captor.getValue();
        assertThat(policy.maxAgeDays()).isNull();
        assertThat(policy.maxCasesPerType()).isEqualTo(500);
    }

    @Test
    void purge_callsStoreWithBothThresholds() {
        when(config.maxAgeDays()).thenReturn(Optional.of(60));
        when(config.maxCasesPerType()).thenReturn(Optional.of(1000));
        when(store.purge(any())).thenReturn(12);

        var job = new CbrRetentionJob(store, config, TENANT_ID);
        job.purge();

        var captor = ArgumentCaptor.forClass(CbrRetentionPolicy.class);
        verify(store).purge(captor.capture());

        CbrRetentionPolicy policy = captor.getValue();
        assertThat(policy.maxAgeDays()).isEqualTo(60);
        assertThat(policy.maxCasesPerType()).isEqualTo(1000);
    }

    @Test
    void constructor_rejectsNonPositiveMaxAgeDays() {
        when(config.maxAgeDays()).thenReturn(Optional.of(0));
        assertThatThrownBy(() -> new CbrRetentionJob(store, config, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-age-days");
    }

    @Test
    void constructor_rejectsNonPositiveMaxCasesPerType() {
        when(config.maxCasesPerType()).thenReturn(Optional.of(0));
        assertThatThrownBy(() -> new CbrRetentionJob(store, config, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-cases-per-type");
    }
}

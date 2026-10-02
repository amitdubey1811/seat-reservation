package com.amitdubey.seats.serviceconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The behaviour under test is the fail-safe path: a broken row must never stop the service
 * serving. Everything else in this codebase fails closed; configuration is the deliberate
 * exception, so it deserves a test that proves it.
 */
@ExtendWith(MockitoExtension.class)
class ServiceConfigServiceTest {

    @Mock
    private ServiceConfigRepository repository;

    private ServiceConfigService service;

    private static ServiceConfigEntry entry(String key, String value) {
        return new ServiceConfigEntry(key, value, "test");
    }

    private void tableContains(ServiceConfigEntry... entries) {
        when(repository.findAll()).thenReturn(List.of(entries));
    }

    @BeforeEach
    void setUp() {
        service = new ServiceConfigService(repository, new SimpleMeterRegistry());
    }

    @Test
    void readsValuesFromTheTable() {
        tableContains(
                entry(ServiceConfigKeys.PER_USER_LIMIT, "7"),
                entry(ServiceConfigKeys.MAX_SEATS_PER_REQUEST, "3"),
                entry(ServiceConfigKeys.IDEMPOTENCY_RETENTION_HOURS, "48"));

        assertThat(service.refresh()).isTrue();

        ConfigSnapshot snapshot = service.snapshot();
        assertThat(snapshot.perUserLimit()).isEqualTo(7);
        assertThat(snapshot.maxSeatsPerRequest()).isEqualTo(3);
        assertThat(snapshot.idempotencyRetentionHours()).isEqualTo(48);
        assertThat(snapshot.version()).isEqualTo(1L);
    }

    @Test
    void anUnparseableValueKeepsTheLastGoodOne() {
        tableContains(entry(ServiceConfigKeys.PER_USER_LIMIT, "7"));
        service.refresh();

        tableContains(entry(ServiceConfigKeys.PER_USER_LIMIT, "abc"));
        service.refresh();

        assertThat(service.snapshot().perUserLimit())
                .as("a typo must not change behaviour, and must not stop the service")
                .isEqualTo(7);
    }

    @Test
    void aZeroOrNegativeLimitIsRefused() {
        tableContains(entry(ServiceConfigKeys.PER_USER_LIMIT, "4"));
        service.refresh();

        tableContains(entry(ServiceConfigKeys.PER_USER_LIMIT, "0"));
        service.refresh();

        assertThat(service.snapshot().perUserLimit())
                .as("a limit of zero would make every booking impossible")
                .isEqualTo(4);
    }

    @Test
    void aMissingRowFallsBackRatherThanFailing() {
        tableContains();

        assertThat(service.refresh()).isTrue();
        assertThat(service.snapshot().perUserLimit())
                .isEqualTo(ServiceConfigKeys.DEFAULT_PER_USER_LIMIT);
    }

    @Test
    void anUnreadableTableKeepsServingOnTheLastGoodSnapshot() {
        tableContains(entry(ServiceConfigKeys.PER_USER_LIMIT, "9"));
        service.refresh();

        when(repository.findAll()).thenThrow(new RuntimeException("database is gone"));

        assertThat(service.refresh()).isFalse();
        assertThat(service.snapshot().perUserLimit()).isEqualTo(9);
    }

    @Test
    void defaultsApplyBeforeAnythingHasBeenLoaded() {
        ConfigSnapshot snapshot = service.snapshot();

        assertThat(snapshot.perUserLimit()).isEqualTo(ServiceConfigKeys.DEFAULT_PER_USER_LIMIT);
        assertThat(snapshot.version()).isZero();
    }
}

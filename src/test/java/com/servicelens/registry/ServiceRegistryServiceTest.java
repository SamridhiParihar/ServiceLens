package com.servicelens.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ServiceRegistryService}.
 *
 * <p>{@link ServiceRegistryRepository} is mocked — no database required.</p>
 */
@DisplayName("ServiceRegistryService")
@ExtendWith(MockitoExtension.class)
class ServiceRegistryServiceTest {

    @Mock
    private ServiceRegistryRepository repository;

    private ServiceRegistryService service;

    private static final String SERVICE = "payment-service";
    private static final String REPO    = "/home/dev/payment-service";

    @BeforeEach
    void setUp() {
        service = new ServiceRegistryService(repository);
    }

    // ── isRegistered ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("isRegistered()")
    class IsRegisteredTests {

        @Test
        @DisplayName("Returns true when repository reports the service exists")
        void returnsTrueWhenExists() {
            when(repository.exists(SERVICE)).thenReturn(true);
            assertThat(service.isRegistered(SERVICE)).isTrue();
        }

        @Test
        @DisplayName("Returns false when repository reports the service is absent")
        void returnsFalseWhenAbsent() {
            when(repository.exists(SERVICE)).thenReturn(false);
            assertThat(service.isRegistered(SERVICE)).isFalse();
        }
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("register()")
    class RegisterTests {

        @Test
        @DisplayName("Upserts a record with ACTIVE status and the given file count")
        void upsertsActiveRecord() {
            service.register(SERVICE, REPO, 42);

            verify(repository).upsert(argThat(r ->
                    r.serviceName().equals(SERVICE) &&
                    r.repoPath().equals(REPO)       &&
                    r.status() == ServiceStatus.ACTIVE &&
                    r.fileCount() == 42));
        }

        @Test
        @DisplayName("Returns the persisted record with correct serviceName")
        void returnsRecordWithServiceName() {
            ServiceRecord result = service.register(SERVICE, REPO, 5);
            assertThat(result.serviceName()).isEqualTo(SERVICE);
            assertThat(result.status()).isEqualTo(ServiceStatus.ACTIVE);
        }
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("update()")
    class UpdateTests {

        @Test
        @DisplayName("Preserves original ingestedAt when service already exists")
        void preservesOriginalIngestedAt() {
            Instant original = Instant.parse("2026-01-01T10:00:00Z");
            when(repository.findByServiceName(SERVICE)).thenReturn(Optional.of(
                    new ServiceRecord(SERVICE, REPO, ServiceStatus.ACTIVE, original, original, 10)));

            service.update(SERVICE, REPO, 20);

            verify(repository).upsert(argThat(r -> r.ingestedAt().equals(original)));
        }

        @Test
        @DisplayName("Updates file count to the new value")
        void updatesFileCount() {
            when(repository.findByServiceName(SERVICE)).thenReturn(Optional.empty());

            service.update(SERVICE, REPO, 99);

            verify(repository).upsert(argThat(r -> r.fileCount() == 99));
        }
    }

    // ── status transitions ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Status transitions")
    class StatusTransitionTests {

        @Test
        @DisplayName("markDeleting() sets status to DELETING")
        void markDeletingSetsStatus() {
            service.markDeleting(SERVICE);
            verify(repository).updateStatus(SERVICE, ServiceStatus.DELETING);
        }

        @Test
        @DisplayName("markIngesting() sets status to INGESTING")
        void markIngestingSetsStatus() {
            service.markIngesting(SERVICE);
            verify(repository).updateStatus(SERVICE, ServiceStatus.INGESTING);
        }
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("remove()")
    class RemoveTests {

        @Test
        @DisplayName("Delegates to repository.delete with the correct service name")
        void delegatesToRepositoryDelete() {
            service.remove(SERVICE);
            verify(repository).delete(SERVICE);
        }
    }

    // ── find / listAll ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("find() and listAll()")
    class QueryTests {

        @Test
        @DisplayName("find() returns the record when it exists")
        void findReturnsRecord() {
            ServiceRecord record = new ServiceRecord(
                    SERVICE, REPO, ServiceStatus.ACTIVE, Instant.now(), Instant.now(), 5);
            when(repository.findByServiceName(SERVICE)).thenReturn(Optional.of(record));

            assertThat(service.find(SERVICE)).contains(record);
        }

        @Test
        @DisplayName("find() returns empty when service is not registered")
        void findReturnsEmptyWhenAbsent() {
            when(repository.findByServiceName(SERVICE)).thenReturn(Optional.empty());
            assertThat(service.find(SERVICE)).isEmpty();
        }

        @Test
        @DisplayName("listAll() delegates to repository.findAll()")
        void listAllDelegatesToRepo() {
            List<ServiceRecord> all = List.of(
                    new ServiceRecord(SERVICE, REPO, ServiceStatus.ACTIVE,
                            Instant.now(), Instant.now(), 5));
            when(repository.findAll()).thenReturn(all);

            assertThat(service.listAll()).isEqualTo(all);
        }
    }
}

package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A recorded quota increase request is stored through the configured storage backend, so it must
 * come back after a restart in a persistent mode. Kept in its own class: a second open PR appends
 * tests to the end of {@code ServiceQuotasServiceTest}, and two PRs appending to one file collide.
 */
class ServiceQuotasPersistenceTest {

    @Test
    void requestsSurviveRestartOnPersistentStorage(@TempDir Path dir) {
        Path file = dir.resolve("servicequotas-requests.json");
        TypeReference<Map<String, ObjectNode>> type = new TypeReference<>() {};
        PersistentStorage<String, ObjectNode> first = new PersistentStorage<>(file, type);
        String id = new ServiceQuotasService(new ObjectMapper(), first)
                .requestServiceQuotaIncrease("codebuild", "L-2DC20C30", 5000.0, null, "us-east-1", "000000000000")
                .path("RequestedQuota").path("Id").asText();

        PersistentStorage<String, ObjectNode> second = new PersistentStorage<>(file, type);
        second.load();
        ServiceQuotasService restarted = new ServiceQuotasService(new ObjectMapper(), second);
        assertEquals(5000.0, restarted.getRequestedServiceQuotaChange(id, "us-east-1")
                .path("RequestedQuota").path("DesiredValue").asDouble());
    }
}

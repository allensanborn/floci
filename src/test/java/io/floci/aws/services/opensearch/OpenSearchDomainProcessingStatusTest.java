package io.floci.aws.services.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.opensearch.model.Domain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Terraform AWS provider's create waiter polls {@code DomainProcessingStatus}. A domain whose
 * container is still starting must read {@code Creating}; the integration tests only see domains
 * that are already ready.
 */
class OpenSearchDomainProcessingStatusTest {

    private final OpenSearchController controller = new OpenSearchController(
            Mockito.mock(OpenSearchService.class), null, new ObjectMapper());

    private String statusOf(boolean processing, boolean deleted) {
        Domain domain = new Domain();
        domain.setDomainName("d");
        domain.setProcessing(processing);
        domain.setDeleted(deleted);
        return controller.toDomainStatusNode(domain).get("DomainProcessingStatus").asText();
    }

    @Test
    void reportsCreatingWhileProcessingActiveWhenReadyAndDeletingOnceDeleted() {
        assertEquals("Creating", statusOf(true, false));
        assertEquals("Active", statusOf(false, false));
        assertEquals("Deleting", statusOf(false, true));
    }
}

package io.floci.aws.services.cloudwatch.logs;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourcePolicy;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudWatchLogsResourcePolicyIntegrationTest {

    private static final String DOCUMENT = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    @Test
    void deleteResourcePolicyRemovesOnlyTheNamedPolicy() {
        String keep = "keep-" + UUID.randomUUID();
        String drop = "drop-" + UUID.randomUUID();
        try (CloudWatchLogsClient logs = client()) {
            logs.putResourcePolicy(request -> request.policyName(keep).policyDocument(DOCUMENT));
            logs.putResourcePolicy(request -> request.policyName(drop).policyDocument(DOCUMENT));

            logs.deleteResourcePolicy(request -> request.policyName(drop));

            List<String> names = logs.describeResourcePolicies(request -> { }).resourcePolicies().stream()
                    .map(ResourcePolicy::policyName).toList();
            assertTrue(names.contains(keep));
            assertFalse(names.contains(drop));

            logs.deleteResourcePolicy(request -> request.policyName(keep));
        }
    }

    @Test
    void deleteResourcePolicyOnAnUnknownNameIsResourceNotFound() {
        try (CloudWatchLogsClient logs = client()) {
            ResourceNotFoundException e = assertThrows(ResourceNotFoundException.class,
                    () -> logs.deleteResourcePolicy(request -> request.policyName("absent-" + UUID.randomUUID())));
            assertEquals("The specified resource does not exist.", e.awsErrorDetails().errorMessage());
        }
    }

    @Test
    void deleteResourcePolicyWithoutAPolicyNameIsInvalid() {
        try (CloudWatchLogsClient logs = client()) {
            assertThrows(InvalidParameterException.class, () -> logs.deleteResourcePolicy(request -> { }));
        }
    }

    private static CloudWatchLogsClient client() {
        return CloudWatchLogsClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }
}

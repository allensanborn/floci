package io.github.hectorvent.floci.services.securityhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.securityhub.model.SecurityHubState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityHubServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "222222222222";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecurityHubService service;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.getPartition()).thenReturn("aws");
        service = new SecurityHubService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), regionResolver,
                mock(OrganizationsService.class));
    }

    @Test
    void enableSecurityHubPersistsConfigurationAndHubTags() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("""
                {
                  "ControlFindingGenerator": "STANDARD_CONTROL",
                  "Tags": {"env": "test"}
                }
                """));

        SecurityHubState state = service.state(REGION);
        assertTrue(state.isEnabled());
        assertEquals("STANDARD_CONTROL", state.getControlFindingGenerator());
        assertEquals(Map.of("env", "test"), service.tagsForResource(REGION, service.hubArn(REGION)));
    }

    @Test
    void enablingTheHubSubscribesTheDefaultStandardsUnlessToldNotTo() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{}"));

        assertEquals(Set.of(
                "arn:aws:securityhub:::ruleset/cis-aws-foundations-benchmark/v/1.2.0",
                "arn:aws:securityhub:us-east-1::standards/aws-foundational-security-best-practices/v/1.0.0"),
                Set.copyOf(service.enabledStandards(REGION, null).values()));
    }

    @Test
    void aBatchDisableWithAnUnknownArnRemovesNothing() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{}"));
        Map<String, String> before = service.enabledStandards(REGION, null);
        String valid = before.keySet().iterator().next();

        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> service.disableStandards(
                REGION, objectMapper.readTree("{\"StandardsSubscriptionArns\": [\"" + valid + "\", \"arn:aws:"
                        + "securityhub:us-east-1:" + ACCOUNT_ID + ":subscription/made-up/v/1.0.0\"]}")))
                .getErrorCode());
        assertEquals(before, service.enabledStandards(REGION, null), "the valid subscription is still there");
    }

    @Test
    void standardsSubscriptionsCanBeEnabledListedAndDisabled() throws Exception {
        assertEquals("InvalidAccessException", assertThrows(AwsException.class,
                () -> service.enabledStandards(REGION, null)).getErrorCode(), "the hub must be enabled first");
        service.enableSecurityHub(REGION, objectMapper.readTree("{\"EnableDefaultStandards\": false}"));
        assertTrue(service.enabledStandards(REGION, null).isEmpty());

        String nist = "arn:aws:securityhub:us-east-1::standards/nist-800-53/v/5.0.0";
        Map<String, String> enabled = service.enableStandards(REGION, objectMapper.readTree(
                "{\"StandardsSubscriptionRequests\": [{\"StandardsArn\": \"" + nist + "\"}]}"));
        String subscription = "arn:aws:securityhub:us-east-1:" + ACCOUNT_ID + ":subscription/nist-800-53/v/5.0.0";
        assertEquals(Map.of(subscription, nist), enabled);
        assertEquals(Map.of(subscription, nist), service.enabledStandards(REGION, objectMapper.readTree(
                "{\"StandardsSubscriptionArns\": [\"" + subscription + "\"]}")));

        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> service.enableStandards(REGION,
                objectMapper.readTree("{\"StandardsSubscriptionRequests\": [{\"StandardsArn\": \"arn:aws:securityhub:"
                        + "us-east-1::standards/made-up/v/1.0.0\"}]}"))).getErrorCode());

        assertEquals(Map.of(subscription, nist), service.disableStandards(REGION, objectMapper.readTree(
                "{\"StandardsSubscriptionArns\": [\"" + subscription + "\"]}")));
        assertTrue(service.enabledStandards(REGION, null).isEmpty());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> service.disableStandards(
                REGION, objectMapper.readTree("{\"StandardsSubscriptionArns\": [\"" + subscription + "\"]}")))
                .getErrorCode());
    }

    /** An account id is scoped to its partition: a China ARN of the same account names nothing here. */
    @Test
    void aResourceArnFromAnotherPartitionIsNotFound() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{\"Tags\": {\"env\": \"test\"}}"));
        String foreign = service.hubArn(REGION).replace("arn:aws:", "arn:aws-cn:");

        AwsException notFound = assertThrows(AwsException.class, () -> service.tagsForResource(REGION, foreign));
        assertEquals("ResourceNotFoundException", notFound.getErrorCode());
        assertEquals(Map.of("env", "test"), service.tagsForResource(REGION, service.hubArn(REGION)));
    }

    @Test
    void invalidReservedTagPrefixIsRejected() throws Exception {
        AwsException error = assertThrows(AwsException.class, () -> service.enableSecurityHub(REGION,
                objectMapper.readTree("{\"Tags\":{\"aws:owner\":\"test\"}}")));

        assertEquals("InvalidInputException", error.getErrorCode());
    }

    @Test
    void invalidAdministratorFeatureIsRejected() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.enableOrganizationAdminAccount(REGION, "111111111111", "Other"));

        assertEquals("InvalidInputException", error.getErrorCode());
    }

    @Test
    void clearRemovesState() throws Exception {
        service.enableSecurityHub(REGION, objectMapper.readTree("{}"));
        assertTrue(service.state(REGION).isEnabled());

        service.clear();

        assertFalse(service.state(REGION).isEnabled());
    }
}

package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.configservice.model.AggregationAuthorization;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AggregationAuthorization is keyed by (AuthorizedAccountId, AuthorizedAwsRegion). Put is an
 * upsert, and the Config model declares no "not found" error for DeleteAggregationAuthorization,
 * so deleting one that is absent succeeds.
 */
class AwsConfigServiceAggregationAuthorizationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String AUTHORIZED_ACCOUNT = "111122223333";

    private AwsConfigService service() {
        return new AwsConfigService(new RegionResolver(REGION, ACCOUNT), null);
    }

    private List<AggregationAuthorization> authorizations(AwsConfigService service, String region) {
        return service.describeAggregationAuthorizations(region, null, null).items();
    }

    @Test
    void putReturnsAnArnAndCreationTime() {
        AwsConfigService service = service();

        AggregationAuthorization created =
                service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);

        AwsArnUtils.Arn arn = AwsArnUtils.parse(created.aggregationAuthorizationArn());
        assertEquals("aws", arn.partition());
        assertEquals("config", arn.service());
        assertEquals(REGION, arn.region());
        assertEquals(ACCOUNT, arn.accountId());
        assertEquals("aggregation-authorization/" + AUTHORIZED_ACCOUNT + "/eu-west-1", arn.resource());
        assertEquals(AUTHORIZED_ACCOUNT, created.authorizedAccountId());
        assertEquals("eu-west-1", created.authorizedAwsRegion());
        assertNotNull(created.creationTime(), "CreationTime must be populated");
    }

    @Test
    void putIsIdempotentForTheSameAccountAndRegion() {
        AwsConfigService service = service();

        AggregationAuthorization first =
                service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
        AggregationAuthorization second =
                service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);

        assertEquals(first.aggregationAuthorizationArn(), second.aggregationAuthorizationArn());
        assertEquals(first.creationTime(), second.creationTime(),
                "re-authorizing must not restart the creation time");
        assertEquals(1, authorizations(service, REGION).size());
    }

    @Test
    void describeListsEveryAuthorizationSortedByAccountThenRegion() {
        AwsConfigService service = service();
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "ap-south-1", null);
        service.putAggregationAuthorization(REGION, "444455556666", "eu-west-1", null);

        List<AggregationAuthorization> listed = authorizations(service, REGION);

        assertEquals(List.of(
                        AUTHORIZED_ACCOUNT + "|ap-south-1",
                        AUTHORIZED_ACCOUNT + "|eu-west-1",
                        "444455556666|eu-west-1"),
                listed.stream()
                        .map(a -> a.authorizedAccountId() + "|" + a.authorizedAwsRegion())
                        .toList());
    }

    @Test
    void describeIsScopedToTheRequestRegion() {
        AwsConfigService service = service();
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);

        assertEquals(1, authorizations(service, REGION).size());
        assertTrue(authorizations(service, "eu-central-1").isEmpty(),
                "an authorization must not leak into another region's aggregator");
    }

    @Test
    void deleteRemovesOnlyTheNamedAuthorization() {
        AwsConfigService service = service();
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "ap-south-1", null);

        service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1");

        assertEquals(List.of("ap-south-1"),
                authorizations(service, REGION).stream()
                        .map(AggregationAuthorization::authorizedAwsRegion).toList());
    }

    @Test
    void deletingAnAbsentAuthorizationSucceeds() {
        AwsConfigService service = service();

        assertDoesNotThrow(() -> service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1"));
    }

    @Test
    void tagsSuppliedOnPutAreReadableThroughListTagsForResource() {
        AwsConfigService service = service();

        AggregationAuthorization created = service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT,
                "eu-west-1", List.of(Map.of("Key", "env", "Value", "prod")));

        List<Map<String, String>> tags = service.listTagsForResource(created.aggregationAuthorizationArn());
        assertEquals(List.of(Map.of("Key", "env", "Value", "prod")), tags);
    }

    @Test
    void deleteDropsTheAuthorizationTagsSoARecreateStartsClean() {
        AwsConfigService service = service();
        AggregationAuthorization created = service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT,
                "eu-west-1", List.of(Map.of("Key", "env", "Value", "prod")));

        service.deleteAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1");
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);

        assertTrue(service.listTagsForResource(created.aggregationAuthorizationArn()).isEmpty(),
                "tags of a deleted authorization must not resurface on the reused ARN");
    }

    @Test
    void rejectsAnAccountIdThatIsNotTwelveDigits() {
        AwsConfigService service = service();

        AwsException ex = assertThrows(AwsException.class,
                () -> service.putAggregationAuthorization(REGION, "12345", "eu-west-1", null));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void rejectsAMissingAuthorizedRegion() {
        AwsConfigService service = service();

        AwsException ex = assertThrows(AwsException.class,
                () -> service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "  ", null));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }

    @Test
    void rejectsALimitAboveTheModeledMaximum() {
        AwsConfigService service = service();

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeAggregationAuthorizations(REGION, 101, null));
        assertEquals("InvalidLimitException", ex.getErrorCode());
    }

    @Test
    void describePaginatesWithNextToken() {
        AwsConfigService service = service();
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "eu-west-1", null);
        service.putAggregationAuthorization(REGION, AUTHORIZED_ACCOUNT, "ap-south-1", null);

        AwsConfigService.Paged<AggregationAuthorization> first =
                service.describeAggregationAuthorizations(REGION, 1, null);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());

        AwsConfigService.Paged<AggregationAuthorization> second =
                service.describeAggregationAuthorizations(REGION, 1, first.nextToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextToken());
        assertEquals("eu-west-1", second.items().getFirst().authorizedAwsRegion());
    }
}

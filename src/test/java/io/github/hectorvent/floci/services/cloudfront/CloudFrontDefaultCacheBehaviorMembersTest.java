package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.xml.HasXPath.hasXPath;

/**
 * DefaultCacheBehavior members that a caller may omit on input but still expects to read
 * back. All are "Required: No" on the request, and CloudFront reports every one of them
 * on every response; the AWS SDKs and the Terraform provider read them unconditionally.
 */
@QuarkusTest
class CloudFrontDefaultCacheBehaviorMembersTest {

    private static final String API = "/2020-05-31/distribution/";
    private static final String DCB = "//*[local-name()='DefaultCacheBehavior']";

    @Inject
    CloudFrontService cloudFrontService;

    private Origin origin(String originId) {
        Origin origin = new Origin();
        origin.setId(originId);
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);
        return origin;
    }

    private String createDistribution(String comment, Origin origin, DefaultCacheBehavior behavior) {
        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setComment(comment);
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return cloudFrontService.createDistribution(distribution, Map.of()).getId();
    }

    /**
     * The Terraform provider's flattenDefaultCacheBehavior reads every one of these without a nil
     * check. TrustedSigners and ForwardedValues are structs it dereferences to reach Quantity and
     * QueryString, so their absence faults the provider process; the TTLs surface as a perpetual
     * diff on a distribution that has converged.
     */
    @Test
    void getDistributionReportsTheCacheKeyMembers() {
        Origin origin = origin("origin-dcb");
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("origin-dcb");
        behavior.setViewerProtocolPolicy("allow-all");
        String id = createDistribution("dcb-members-test", origin, behavior);

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath(DCB + "/*[local-name()='TrustedSigners']/*[local-name()='Enabled']",
                    equalTo("false")))
            .body(hasXPath(DCB + "/*[local-name()='TrustedSigners']/*[local-name()='Quantity']",
                    equalTo("0")))
            .body(hasXPath(DCB + "/*[local-name()='SmoothStreaming']", equalTo("false")))
            .body(hasXPath(DCB + "/*[local-name()='ForwardedValues']"
                    + "/*[local-name()='QueryString']", equalTo("false")))
            .body(hasXPath(DCB + "/*[local-name()='ForwardedValues']"
                    + "/*[local-name()='Cookies']/*[local-name()='Forward']", equalTo("none")))
            .body(hasXPath(DCB + "/*[local-name()='ForwardedValues']"
                    + "/*[local-name()='Headers']/*[local-name()='Quantity']", equalTo("0")))
            .body(hasXPath(DCB + "/*[local-name()='ForwardedValues']"
                    + "/*[local-name()='QueryStringCacheKeys']/*[local-name()='Quantity']",
                    equalTo("0")))
            .body(hasXPath(DCB + "/*[local-name()='MinTTL']", equalTo("0")))
            .body(hasXPath(DCB + "/*[local-name()='DefaultTTL']", equalTo("0")))
            .body(hasXPath(DCB + "/*[local-name()='MaxTTL']", equalTo("0")));
    }

    /** Explicit TTLs are echoed back, not flattened to the defaults. */
    @Test
    void explicitCacheBehaviorTtlsAreReportedBack() {
        Origin origin = origin("origin-ttl");
        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("origin-ttl");
        behavior.setViewerProtocolPolicy("allow-all");
        behavior.setMinTTL(1);
        behavior.setDefaultTTL(3600);
        behavior.setMaxTTL(86400);
        String id = createDistribution("dcb-members-test-ttl", origin, behavior);

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath(DCB + "/*[local-name()='MinTTL']", equalTo("1")))
            .body(hasXPath(DCB + "/*[local-name()='DefaultTTL']", equalTo("3600")))
            .body(hasXPath(DCB + "/*[local-name()='MaxTTL']", equalTo("86400")));
    }
}

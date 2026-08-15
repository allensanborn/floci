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
 * DistributionConfig members that a caller may omit on input but still expects to read
 * back. Restrictions, Logging and OriginGroups are all "Required: No" on the request, and
 * CloudFront reports them on every response with their defaults; the AWS SDKs and the
 * Terraform provider read them unconditionally.
 */
@QuarkusTest
class CloudFrontDistributionConfigMembersTest {

    private static final String API = "/2020-05-31/distribution/";

    @Inject
    CloudFrontService cloudFrontService;

    /** The minimum a caller can legally supply: no Restrictions, Logging or OriginGroups. */
    private String createMinimalDistribution(String originId) {
        Origin origin = new Origin();
        origin.setId(originId);
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);

        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId(originId);
        behavior.setViewerProtocolPolicy("allow-all");

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setComment("members-test");
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        return cloudFrontService.createDistribution(distribution, Map.of()).getId();
    }

    @Test
    void getDistributionReportsGeoRestrictionDefaultedToNone() {
        String id = createMinimalDistribution("origin-geo");

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='Restrictions']"
                    + "/*[local-name()='GeoRestriction']/*[local-name()='RestrictionType']",
                    equalTo("none")))
            .body(hasXPath("//*[local-name()='Restrictions']"
                    + "/*[local-name()='GeoRestriction']/*[local-name()='Quantity']",
                    equalTo("0")));
    }

    @Test
    void getDistributionReportsLoggingDisabledByDefault() {
        String id = createMinimalDistribution("origin-logging");

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='Logging']/*[local-name()='Enabled']",
                    equalTo("false")));
    }

    @Test
    void getDistributionReportsAnEmptyOriginGroupCollection() {
        String id = createMinimalDistribution("origin-groups");

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='OriginGroups']/*[local-name()='Quantity']",
                    equalTo("0")));
    }

    @Test
    void createDistributionReportsTheSameMembersAsGetDistribution() {
        // The Terraform provider reads the resource back immediately after create, so the
        // create response has to carry these members too -- reporting them only on
        // GetDistribution would still fail the read-after-create.
        String id = createMinimalDistribution("origin-create");

        given()
        .when()
            .get(API + id + "/config")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='Restrictions']"
                    + "/*[local-name()='GeoRestriction']/*[local-name()='RestrictionType']",
                    equalTo("none")))
            .body(hasXPath("//*[local-name()='Logging']/*[local-name()='Enabled']",
                    equalTo("false")))
            .body(hasXPath("//*[local-name()='OriginGroups']/*[local-name()='Quantity']",
                    equalTo("0")));
    }

    @Test
    void anExplicitGeoRestrictionIsReportedBackInFull() {
        Origin origin = new Origin();
        origin.setId("origin-explicit");
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);

        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("origin-explicit");
        behavior.setViewerProtocolPolicy("allow-all");

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setComment("members-test-explicit");
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Map<String, Object> geo = new LinkedHashMap<>();
        geo.put("RestrictionType", "whitelist");
        geo.put("Items", List.of("US", "CA"));
        config.setGeoRestriction(geo);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        String id = cloudFrontService.createDistribution(distribution, Map.of()).getId();

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='Restrictions']"
                    + "/*[local-name()='GeoRestriction']/*[local-name()='RestrictionType']",
                    equalTo("whitelist")))
            .body(hasXPath("//*[local-name()='Restrictions']"
                    + "/*[local-name()='GeoRestriction']/*[local-name()='Quantity']",
                    equalTo("2")))
            .body(hasXPath("count(//*[local-name()='Restrictions']"
                    + "/*[local-name()='GeoRestriction']/*[local-name()='Items']"
                    + "/*[local-name()='Location'])",
                    equalTo("2")));
    }
}

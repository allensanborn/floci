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
 * back. Logging is "Required: No" on the request, and CloudFront reports it on every
 * response with its defaults; the AWS SDKs and the Terraform provider read it
 * unconditionally.
 */
@QuarkusTest
class CloudFrontDistributionConfigMembersTest {

    private static final String API = "/2020-05-31/distribution/";
    private static final String LOGGING_ENABLED =
            "//*[local-name()='Logging']/*[local-name()='Enabled']";

    @Inject
    CloudFrontService cloudFrontService;

    /** The minimum a caller can legally supply: no Logging element at all. */
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
    void getDistributionReportsLoggingDisabledByDefault() {
        String id = createMinimalDistribution("origin-logging");

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("false")))
            .body(hasXPath("//*[local-name()='Logging']/*[local-name()='Bucket']",
                    equalTo("")));
    }

    @Test
    void getDistributionConfigReportsLoggingToo() {
        // The Terraform provider reads the resource back immediately after create, so the
        // config response has to carry the member too: reporting it only on
        // GetDistribution would still leave the read-after-create short a field.
        String id = createMinimalDistribution("origin-logging-config");

        given()
        .when()
            .get(API + id + "/config")
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("false")));
    }

    @Test
    void anExplicitLoggingConfigurationIsReportedBackInFull() {
        Origin origin = new Origin();
        origin.setId("origin-logging-explicit");
        origin.setDomainName("example.com");
        Map<String, Object> customOriginConfig = new LinkedHashMap<>();
        customOriginConfig.put("HTTPPort", "80");
        customOriginConfig.put("HTTPSPort", "443");
        customOriginConfig.put("OriginProtocolPolicy", "http-only");
        origin.setCustomOriginConfig(customOriginConfig);

        DefaultCacheBehavior behavior = new DefaultCacheBehavior();
        behavior.setTargetOriginId("origin-logging-explicit");
        behavior.setViewerProtocolPolicy("allow-all");

        DistributionConfig config = new DistributionConfig();
        config.setEnabled(true);
        config.setComment("members-test-explicit");
        config.setOrigins(List.of(origin));
        config.setDefaultCacheBehavior(behavior);
        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put("Enabled", "true");
        logging.put("IncludeCookies", "true");
        logging.put("Bucket", "logs.s3.amazonaws.com");
        logging.put("Prefix", "cf/");
        config.setLogging(logging);

        Distribution distribution = new Distribution();
        distribution.setConfig(config);
        String id = cloudFrontService.createDistribution(distribution, Map.of()).getId();

        given()
        .when()
            .get(API + id)
        .then()
            .statusCode(200)
            .body(hasXPath(LOGGING_ENABLED, equalTo("true")))
            .body(hasXPath("//*[local-name()='Logging']/*[local-name()='IncludeCookies']",
                    equalTo("true")))
            .body(hasXPath("//*[local-name()='Logging']/*[local-name()='Bucket']",
                    equalTo("logs.s3.amazonaws.com")))
            .body(hasXPath("//*[local-name()='Logging']/*[local-name()='Prefix']",
                    equalTo("cf/")));
    }
}

package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ConfigurationAggregatorIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
            .header("X-Amz-Target", "StarlingDoveService." + action)
            .contentType("application/x-amz-json-1.1")
            .body(body)
        .when()
            .post("/");
    }

    private static final String ACCOUNTS = """
            [{"AccountIds": ["111122223333"], "AllAwsRegions": true}]""";

    @Test
    void putDescribeUpdateInPlaceTagsAndDelete() throws Exception {
        String name = "agg-lifecycle";
        String arn = call("PutConfigurationAggregator", """
            {"ConfigurationAggregatorName": "%s", "AccountAggregationSources": %s,
             "Tags": [{"Key": "env", "Value": "prod"}]}""".formatted(name, ACCOUNTS))
            .then().statusCode(200)
            .body("ConfigurationAggregator.ConfigurationAggregatorName", equalTo(name))
            .body("ConfigurationAggregator.ConfigurationAggregatorArn",
                    matchesPattern("arn:aws:config:[a-z0-9-]+:\\d{12}:config-aggregator/config-aggregator-[a-z0-9]+"))
            .body("ConfigurationAggregator.AccountAggregationSources[0].AccountIds[0]", equalTo("111122223333"))
            .body("ConfigurationAggregator.AccountAggregationSources[0].AllAwsRegions", equalTo(true))
            .body("ConfigurationAggregator.CreationTime", notNullValue())
            .extract().path("ConfigurationAggregator.ConfigurationAggregatorArn");

        call("DescribeConfigurationAggregators", "{\"ConfigurationAggregatorNames\": [\"" + name + "\"]}")
            .then().statusCode(200)
            .body("ConfigurationAggregators", hasSize(1))
            .body("ConfigurationAggregators[0].ConfigurationAggregatorArn", equalTo(arn));

        call("ListTagsForResource", "{\"ResourceArn\": \"" + arn + "\"}")
            .then().statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"));
        call("TagResource", "{\"ResourceArn\": \"" + arn + "\", \"Tags\": [{\"Key\": \"k2\", \"Value\": \"v2\"}]}")
            .then().statusCode(200);
        call("UntagResource", "{\"ResourceArn\": \"" + arn + "\", \"TagKeys\": [\"env\"]}").then().statusCode(200);
        call("ListTagsForResource", "{\"ResourceArn\": \"" + arn + "\"}")
            .then().body("Tags", hasSize(1)).body("Tags[0].Key", equalTo("k2"));

        // update in place: same ARN, new body
        call("PutConfigurationAggregator", """
            {"ConfigurationAggregatorName": "%s",
             "OrganizationAggregationSource": {"RoleArn": "arn:aws:iam::000000000000:role/r", "AllAwsRegions": true}}"""
            .formatted(name))
            .then().statusCode(200)
            .body("ConfigurationAggregator.ConfigurationAggregatorArn", equalTo(arn))
            .body("ConfigurationAggregator.LastUpdatedTime", notNullValue());
        call("DescribeConfigurationAggregators", "{}")
            .then().body("ConfigurationAggregators.findAll { it.ConfigurationAggregatorName == '" + name + "' }",
                    hasSize(1))
            .body("ConfigurationAggregators.find { it.ConfigurationAggregatorName == '" + name
                    + "' }.OrganizationAggregationSource.RoleArn", equalTo("arn:aws:iam::000000000000:role/r"))
            .body("ConfigurationAggregators.find { it.ConfigurationAggregatorName == '" + name
                    + "' }.AccountAggregationSources", nullValue());

        call("DeleteConfigurationAggregator", "{\"ConfigurationAggregatorName\": \"" + name + "\"}")
            .then().statusCode(200);
        call("DescribeConfigurationAggregators", "{\"ConfigurationAggregatorNames\": [\"" + name + "\"]}")
            .then().statusCode(400).body("__type", equalTo("NoSuchConfigurationAggregatorException"));
        call("ListTagsForResource", "{\"ResourceArn\": \"" + arn + "\"}")
            .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteUnknownIsNotFound() {
        call("DeleteConfigurationAggregator", "{\"ConfigurationAggregatorName\": \"nope\"}")
            .then().statusCode(400).body("__type", equalTo("NoSuchConfigurationAggregatorException"));
    }

    @Test
    void rejectsInvalidSources() {
        String org = "{\"RoleArn\": \"arn:aws:iam::000000000000:role/r\"}";
        String[] bad = {
            "{\"ConfigurationAggregatorName\": \"v1\"}",
            "{\"ConfigurationAggregatorName\": \"v2\", \"AccountAggregationSources\": " + ACCOUNTS
                    + ", \"OrganizationAggregationSource\": " + org + "}",
            "{\"ConfigurationAggregatorName\": \"v3\", \"AccountAggregationSources\": [{\"AccountIds\": [\"12\"]}]}",
            "{\"ConfigurationAggregatorName\": \"bad name!\", \"AccountAggregationSources\": " + ACCOUNTS + "}",
            "{\"ConfigurationAggregatorName\": \"v5\", \"OrganizationAggregationSource\": {}}",
        };
        for (String body : bad) {
            call("PutConfigurationAggregator", body).then().statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
        }
        call("DescribeConfigurationAggregators", "{}")
            .then().body("ConfigurationAggregators.ConfigurationAggregatorName", not(hasItem("v1")));
    }
}

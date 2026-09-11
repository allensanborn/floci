package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class DmsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonDMSv20160101.";
    private static final String ACCOUNT_ID = "723679240095";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260101/us-east-1/dms/aws4_request";
    private static final String SUBNET_A = "subnet-default-us-east-1-a";
    private static final String SUBNET_B = "subnet-default-us-east-1-b";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void subnetGroupLifecycleIsReadableThroughDescribe() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"Tf-Lifecycle\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationSubnetGroup.ReplicationSubnetGroupIdentifier", equalTo("tf-lifecycle"));

        dms("DescribeReplicationSubnetGroups")
                .body("{\"Filters\":[{\"Name\":\"replication-subnet-group-id\","
                        + "\"Values\":[\"tf-lifecycle\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ReplicationSubnetGroups", hasSize(1))
                .body("ReplicationSubnetGroups[0].ReplicationSubnetGroupDescription",
                        equalTo("terraform managed"))
                .body("ReplicationSubnetGroups[0].VpcId", equalTo("vpc-default-us-east-1"))
                .body("ReplicationSubnetGroups[0].SubnetGroupStatus", equalTo("Complete"))
                .body("ReplicationSubnetGroups[0].Subnets.SubnetIdentifier", contains(SUBNET_A, SUBNET_B))
                .body("ReplicationSubnetGroups[0].Subnets.find { it.SubnetIdentifier == '"
                        + SUBNET_A + "' }.SubnetAvailabilityZone.Name", equalTo("us-east-1a"))
                .body("ReplicationSubnetGroups[0].Subnets.find { it.SubnetIdentifier == '"
                        + SUBNET_B + "' }.SubnetAvailabilityZone.Name", equalTo("us-east-1b"))
                .body("ReplicationSubnetGroups[0].Subnets.SubnetStatus", everyItem(equalTo("Active")))
                .body("ReplicationSubnetGroups[0].SupportedNetworkTypes", contains("IPV4"));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-lifecycle\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("DescribeReplicationSubnetGroups")
                .body("{\"Filters\":[{\"Name\":\"replication-subnet-group-id\","
                        + "\"Values\":[\"tf-lifecycle\"]}]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void createRejectsADuplicateIdentifier() {
        dms("CreateReplicationSubnetGroup")
                .body(createBody("tf-duplicate"))
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("CreateReplicationSubnetGroup")
                .body(createBody("tf-duplicate"))
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsFault"));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-duplicate\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void createRejectsSubnetsInASingleAvailabilityZone() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-one-az\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ReplicationSubnetGroupDoesNotCoverEnoughAZs"));
    }

    @Test
    void createRejectsAnUnknownSubnet() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-bad-subnet\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"subnet-does-not-exist\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidSubnet"));
    }

    @Test
    void createRejectsAMissingDescription() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-no-description\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    void deleteOfAMissingSubnetGroupFaults() {
        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-absent\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void unsupportedDmsActionReportsUnknownOperation() {
        dms("CreateReplicationInstance")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(404)
                .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void tagsSurviveCreateAndAreReadableThroughListTagsForResource() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-tagged\","
                        + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"],"
                        + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn("tf-tagged") + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Key", equalTo("env"))
                .body("TagList[0].Value", equalTo("test"))
                .body("TagList[0].ResourceArn", nullValue());

        dms("AddTagsToResource")
                .body("{\"ResourceArn\":\"" + arn("tf-tagged") + "\","
                        + "\"Tags\":[{\"Key\":\"owner\",\"Value\":\"data\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn("tf-tagged") + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("TagList.Key", containsInAnyOrder("env", "owner"));

        dms("RemoveTagsFromResource")
                .body("{\"ResourceArn\":\"" + arn("tf-tagged") + "\",\"TagKeys\":[\"env\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        dms("ListTagsForResource")
                .body("{\"ResourceArnList\":[\"" + arn("tf-tagged") + "\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Key", equalTo("owner"))
                .body("TagList[0].ResourceArn", equalTo(arn("tf-tagged")));

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"tf-tagged\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void listTagsForAnUnknownArnFaults() {
        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn("tf-never-created") + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    private static String arn(String identifier) {
        return "arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":subgrp:" + identifier;
    }

    private static String createBody(String identifier) {
        return "{\"ReplicationSubnetGroupIdentifier\":\"" + identifier + "\","
                + "\"ReplicationSubnetGroupDescription\":\"terraform managed\","
                + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}";
    }

    private static RequestSpecification dms(String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER);
    }
}

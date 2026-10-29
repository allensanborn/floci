package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.equalTo;

/** {@code BackupRetentionPeriod} and create-time {@code Tags} on {@code CreateDBCluster}, read back. */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterRetentionTagsIntegrationTest {

    private static final String DESCRIBED = "DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters.DBCluster.";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261015/us-east-1/rds/aws4_request, "
            + "SignedHeaders=content-type;host, Signature=test";

    private static RequestSpecification query(String action) {
        return given().header("Authorization", AUTH).contentType(URLENC)
                .formParam("Action", action).formParam("Version", "2014-10-31");
    }

    private static RequestSpecification create(String id) {
        return query("CreateDBCluster").formParam("DBClusterIdentifier", id)
                .formParam("Engine", "aurora-postgresql")
                .formParam("MasterUsername", "admin").formParam("MasterUserPassword", "password123");
    }

    private static void expectRetention(String id, String value) {
        query("DescribeDBClusters").formParam("DBClusterIdentifier", id)
        .when().post("/").then().statusCode(200)
                .body(DESCRIBED + "BackupRetentionPeriod", equalTo(value));
    }

    @Test
    void retentionGivenAtCreateIsReadBackAndModifyChangesIt() {
        create("ret-cluster").formParam("BackupRetentionPeriod", "5")
        .when().post("/").then().statusCode(200);
        expectRetention("ret-cluster", "5");

        query("ModifyDBCluster").formParam("DBClusterIdentifier", "ret-cluster")
                .formParam("BackupRetentionPeriod", "7").formParam("ApplyImmediately", "true")
        .when().post("/").then().statusCode(200);
        expectRetention("ret-cluster", "7");
    }

    @Test
    void retentionDefaultsToOneWhenOmitted() {
        create("ret-default-cluster").when().post("/").then().statusCode(200);
        expectRetention("ret-default-cluster", "1");
    }

    @Test
    void createTimeTagsAreListedAndInTagList() {
        create("tag-cluster").formParam("Tags.Tag.1.Key", "Name").formParam("Tags.Tag.1.Value", "g")
        .when().post("/").then().statusCode(200);

        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:cluster:tag-cluster")
        .when().post("/").then().statusCode(200)
                .body("ListTagsForResourceResponse.ListTagsForResourceResult.TagList.Tag.Key", equalTo("Name"))
                .body("ListTagsForResourceResponse.ListTagsForResourceResult.TagList.Tag.Value", equalTo("g"));

        query("DescribeDBClusters").formParam("DBClusterIdentifier", "tag-cluster")
        .when().post("/").then().statusCode(200)
                .body(DESCRIBED + "TagList.Tag.Key", equalTo("Name"))
                .body(DESCRIBED + "TagList.Tag.Value", equalTo("g"));
    }
}

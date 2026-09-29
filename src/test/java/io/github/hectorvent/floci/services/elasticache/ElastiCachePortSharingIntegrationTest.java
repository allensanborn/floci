package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Two caches may advertise the same port, as they do on AWS.
 *
 * <p>6379 is the Redis default, so nearly every ElastiCache cluster in the world uses it and they
 * coexist: the port belongs to a cluster's own endpoint, not to a global namespace. Floci used to
 * advertise its auth proxy's host port as the AWS one, which made the AWS port globally scarce and
 * refused the second group with {@code InvalidParameterValue}. Any module standing up two Redis
 * clusters, and any suite standing up one per test, failed from the second create onward.
 *
 * <p>These tests assert the control plane only. What the proxy binds is Floci's own business and
 * deliberately invisible here; that the first cache answers RESP on the port it advertises is
 * {@link ElastiCacheIntegrationTest}'s ground.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCachePortSharingIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";

    /** The Redis default, and the port every module in the corpus pins. */
    private static final int SHARED_PORT = 6379;
    /** Outside Floci's proxy range, inside the range AWS accepts. */
    private static final int OFF_RANGE_PORT = 7777;

    private static final String FIRST_GROUP = "it-ec-share-a";
    private static final String SECOND_GROUP = "it-ec-share-b";
    private static final String OFF_RANGE_GROUP = "it-ec-share-c";

    private static final String CREATED_PORT =
            "CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup"
                    + ".NodeGroups.NodeGroup.PrimaryEndpoint.Port";
    private static final String DESCRIBED_PORT =
            "DescribeReplicationGroupsResponse.DescribeReplicationGroupsResult.ReplicationGroups"
                    + ".ReplicationGroup.NodeGroups.NodeGroup.PrimaryEndpoint.Port";
    private static final String MEMBER_NODE_PORT =
            "DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster"
                    + ".CacheNodes.CacheNode.Endpoint.Port";

    @AfterAll
    static void cleanup() {
        for (String groupId : List.of(FIRST_GROUP, SECOND_GROUP, OFF_RANGE_GROUP)) {
            try {
                given()
                    .formParam("Action", "DeleteReplicationGroup")
                    .formParam("ReplicationGroupId", groupId)
                    .header("Authorization", AUTH_HEADER)
                    .post("/");
            } catch (Exception ignored) {
                // Best-effort: a group a test never created has nothing to tear down.
            }
        }
    }

    @Test
    @Order(1)
    void firstGroupAdvertisesThePortItAskedFor() {
        createGroup(FIRST_GROUP, SHARED_PORT)
            .statusCode(200)
            .body(CREATED_PORT, equalTo(String.valueOf(SHARED_PORT)));
    }

    /** The bug: AWS creates this group, and Floci answered InvalidParameterValue. */
    @Test
    @Order(2)
    void secondGroupOnTheSamePortIsCreatedAndAdvertisesIt() {
        createGroup(SECOND_GROUP, SHARED_PORT)
            .statusCode(200)
            .body(CREATED_PORT, equalTo(String.valueOf(SHARED_PORT)));
    }

    @Test
    @Order(3)
    void bothGroupsKeepAdvertisingTheSharedPort() {
        describeGroup(FIRST_GROUP).body(DESCRIBED_PORT, equalTo(String.valueOf(SHARED_PORT)));
        describeGroup(SECOND_GROUP).body(DESCRIBED_PORT, equalTo(String.valueOf(SHARED_PORT)));
    }

    /**
     * The terraform provider reads a member's port out of DescribeCacheClusters, so the two
     * describes have to agree: a member reporting the host port would be a diff no plan settles.
     */
    @Test
    @Order(4)
    void memberCacheClustersReportTheAdvertisedPortToo() {
        describeMember(SECOND_GROUP + "-001")
            .body(MEMBER_NODE_PORT, equalTo(String.valueOf(SHARED_PORT)));
    }

    /**
     * A port outside Floci's proxy range was refused outright, which is an emulator-shaped limit
     * no AWS caller can anticipate. Now that the advertised port is not the bound one, the range
     * constrains only what Floci listens on.
     */
    @Test
    @Order(5)
    void aPortOutsideTheProxyRangeIsAdvertisedRatherThanRefused() {
        createGroup(OFF_RANGE_GROUP, OFF_RANGE_PORT)
            .statusCode(200)
            .body(CREATED_PORT, equalTo(String.valueOf(OFF_RANGE_PORT)));
    }

    /** The range AWS itself enforces, and all this is left of the old rejection. */
    @Test
    @Order(6)
    void aPortOutsideTheRangeAwsAcceptsIsStillRefused() {
        given()
            .formParam("Action", "CreateReplicationGroup")
            .formParam("ReplicationGroupId", "it-ec-share-bad-port")
            .formParam("ReplicationGroupDescription", "port sharing test")
            .formParam("Engine", "redis")
            .formParam("NumCacheClusters", "1")
            .formParam("Port", "80")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidParameterValue"))
            .body(containsString("<Error>"));
    }

    // There is deliberately no create-delete-create test here. Three cycles over a 21-port range
    // cannot exhaust it, so such a test passes whether or not the port is released and asserts
    // nothing. ElastiCacheServiceTest.deletingAGroupReleasesTheHostPortItWasBoundTo asserts the
    // exact port the next create receives, which is the observable that does move when the
    // release is wrong.

    private static io.restassured.response.ValidatableResponse createGroup(String groupId, int port) {
        return given()
                .formParam("Action", "CreateReplicationGroup")
                .formParam("ReplicationGroupId", groupId)
                .formParam("ReplicationGroupDescription", "port sharing test")
                .formParam("Engine", "redis")
                .formParam("NumCacheClusters", "1")
                .formParam("Port", String.valueOf(port))
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then();
    }

    private static io.restassured.response.ValidatableResponse describeGroup(String groupId) {
        return given()
                .formParam("Action", "DescribeReplicationGroups")
                .formParam("ReplicationGroupId", groupId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static io.restassured.response.ValidatableResponse describeMember(String cacheClusterId) {
        return given()
                .formParam("Action", "DescribeCacheClusters")
                .formParam("CacheClusterId", cacheClusterId)
                .formParam("ShowCacheNodeInfo", "true")
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }
}

package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Two caches may both ask for 6379, and each is reachable at the endpoint it reports.
 *
 * <p>6379 is the Redis default, so nearly every ElastiCache cluster uses it and on AWS they
 * coexist: each cache has a DNS name of its own and the port never distinguishes them. Floci has
 * one host, so the port is the only thing that does. It used to refuse the second cache, which
 * fails any module standing up two Redis clusters. It now serves the second cache on a port of
 * its own and reports that port.
 *
 * <p>The assertion that matters is the last one, and it is at the data plane rather than the
 * control plane: writing through each cache's own advertised endpoint and reading it back must
 * return that cache's value. Reporting the pinned port while listening elsewhere would satisfy
 * every field check in this class and still send the second cache's client to the first cache's
 * data.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCachePortSharingIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";

    /** The Redis default, and the port every module in the corpus pins. */
    private static final int SHARED_PORT = 6379;

    private static final String FIRST_GROUP = "it-ec-share-a";
    private static final String SECOND_GROUP = "it-ec-share-b";

    private static final String CREATED_PORT =
            "CreateReplicationGroupResponse.CreateReplicationGroupResult.ReplicationGroup"
                    + ".NodeGroups.NodeGroup.PrimaryEndpoint.Port";
    private static final String DESCRIBED_PORT =
            "DescribeReplicationGroupsResponse.DescribeReplicationGroupsResult.ReplicationGroups"
                    + ".ReplicationGroup.NodeGroups.NodeGroup.PrimaryEndpoint.Port";
    private static final String MEMBER_NODE_PORT =
            "DescribeCacheClustersResponse.DescribeCacheClustersResult.CacheClusters.CacheCluster"
                    + ".CacheNodes.CacheNode.Endpoint.Port";

    private static int firstPort;
    private static int secondPort;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker must be available: the data-plane assertion needs real caches behind the proxies");
    }

    @AfterAll
    static void cleanup() {
        for (String groupId : List.of(FIRST_GROUP, SECOND_GROUP)) {
            try {
                given()
                    .formParam("Action", "DeleteReplicationGroup")
                    .formParam("ReplicationGroupId", groupId)
                    .header("Authorization", AUTH_HEADER)
                    .post("/");
            } catch (Exception ignored) {
                // Best effort: a group a test never created has nothing to tear down.
            }
        }
    }

    @Test
    @Order(1)
    void firstGroupIsServedOnThePortItAskedFor() {
        firstPort = createGroup(FIRST_GROUP, SHARED_PORT)
            .statusCode(200)
            .body(CREATED_PORT, equalTo(String.valueOf(SHARED_PORT)))
            .extract().xmlPath().getInt(CREATED_PORT);
    }

    /** The bug: AWS creates this group, and Floci answered InvalidParameterValue. */
    @Test
    @Order(2)
    void secondGroupOnTheSamePortIsCreatedRatherThanRefused() {
        secondPort = createGroup(SECOND_GROUP, SHARED_PORT)
            .statusCode(200)
            .extract().xmlPath().getInt(CREATED_PORT);

        assertNotEquals(firstPort, secondPort,
                "The second group must be served somewhere of its own, not on the first's port");
    }

    @Test
    @Order(3)
    void eachGroupKeepsReportingThePortItIsOn() {
        assertEquals(firstPort, describeGroup(FIRST_GROUP).extract().xmlPath().getInt(DESCRIBED_PORT));
        assertEquals(secondPort, describeGroup(SECOND_GROUP).extract().xmlPath().getInt(DESCRIBED_PORT));
    }

    /** The terraform provider reads a member's port out of DescribeCacheClusters. */
    @Test
    @Order(4)
    void memberCacheClustersAgreeWithTheGroupEndpoint() {
        assertEquals(secondPort,
                describeMember(SECOND_GROUP + "-001").extract().xmlPath().getInt(MEMBER_NODE_PORT));
    }

    /** AWS's own rule, and the only rejection left. */
    @Test
    @Order(5)
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

    /**
     * The one that would have caught the wrong answer. Each cache is written through the endpoint
     * it advertises and read back through the same one: if either endpoint named the other
     * cache's listener, the second read would return the first cache's value and every assertion
     * above would still have passed.
     */
    @Test
    @Order(6)
    void eachAdvertisedEndpointReachesItsOwnCache() throws Exception {
        send(firstPort, resp("SET", "who", "first"));
        send(secondPort, resp("SET", "who", "second"));

        assertEquals("first", bulkString(send(firstPort, resp("GET", "who"))),
                "The first group's endpoint must reach the first group");
        assertEquals("second", bulkString(send(secondPort, resp("GET", "who"))),
                "and the second group's endpoint must reach the second group, not the first");
    }

    private static ValidatableResponse createGroup(String groupId, int port) {
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

    private static ValidatableResponse describeGroup(String groupId) {
        return given()
                .formParam("Action", "DescribeReplicationGroups")
                .formParam("ReplicationGroupId", groupId)
                .header("Authorization", AUTH_HEADER)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    private static ValidatableResponse describeMember(String cacheClusterId) {
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

    private static String resp(String... args) {
        StringBuilder sb = new StringBuilder("*").append(args.length).append("\r\n");
        for (String arg : args) {
            sb.append('$').append(arg.length()).append("\r\n").append(arg).append("\r\n");
        }
        return sb.toString();
    }

    /** Reads one RESP reply; enough for the +OK and $-prefixed bulk strings these commands give. */
    private static String send(int port, String command) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(command.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[256];
            int read = in.read(buffer);
            return read < 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static String bulkString(String reply) {
        String[] lines = reply.split("\r\n");
        return lines.length > 1 ? lines[1] : reply;
    }

    private static boolean isDockerAvailable() {
        try {
            return new ProcessBuilder("docker", "info").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}

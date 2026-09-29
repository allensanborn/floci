package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two caches may both ask for 6379, and each is reachable at the endpoint it reports.
 *
 * <p>6379 is the Redis default, so nearly every ElastiCache cluster uses it and on AWS they
 * coexist: each cache has a DNS name of its own and the port never distinguishes them. Floci has
 * one host, so the port is the only thing that does. It used to refuse the second cache, which
 * fails any module standing up two Redis clusters. It now serves the second cache on a port of
 * its own and reports that port.
 *
 * <p>Two assertions carry the weight, and it is worth being exact about which rules out what.
 * {@code Order(2)} compares the two REPORTED ports and requires them to differ. One host port can
 * only name one listener, so a build that reported the pinned port on both caches fails there, on
 * the control plane, before anything connects. {@code Order(6)} then writes through each cache's
 * own advertised endpoint and reads its own value back, which rules out two endpoints reaching
 * one cache even when the numbers differ.
 *
 * <p>The useful distinction is not control plane against data plane. It is whether an assertion
 * constrains the thing a wrong design could get wrong. An assertion about a SINGLE reported value
 * cannot: "the API says 6379" is satisfied whatever 6379 actually serves. An assertion about a
 * RELATIONSHIP between reported values often can, which is why {@code Order(2)} bites.
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
    /**
     * A token per group, because the proxy validates it against the group it was started for
     * (see the {@code validatePassword(groupId, ...)} each startProxy is given). That makes the
     * token the one thing a cache knows about its own control-plane identity, and it is what
     * lets {@code Order(6)} tell "reaches its own cache" from "reaches a distinct cache".
     */
    /** What a proxy answers AUTH with another group's token; pinned by ElastiCacheIntegrationTest. */
    private static final String WRONG_TOKEN_ERROR = "invalid username-password pair";
    private static final String FIRST_TOKEN = "token-for-group-a";
    private static final String SECOND_TOKEN = "token-for-group-b";

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
        firstPort = createGroup(FIRST_GROUP, SHARED_PORT, FIRST_TOKEN)
            .statusCode(200)
            .body(CREATED_PORT, equalTo(String.valueOf(SHARED_PORT)))
            .extract().xmlPath().getInt(CREATED_PORT);
    }

    /** The bug: AWS creates this group, and Floci answered InvalidParameterValue. */
    @Test
    @Order(2)
    void secondGroupOnTheSamePortIsCreatedRatherThanRefused() {
        secondPort = createGroup(SECOND_GROUP, SHARED_PORT, SECOND_TOKEN)
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
     * Each group's endpoint reaches that group's own cache.
     *
     * <p>Docker is required here and only here: this is the one test that needs a container
     * behind the proxy. Orders 1 to 5 are control-plane assertions and a group reaches
     * {@code available} with no daemon reachable, so gating the whole class on Docker would
     * delete the regression test for this fix on a Docker-less runner, silently and green.
     *
     * <p>{@code Order(2)} already rejects a build reporting one port for both caches. Writing and
     * reading through each endpoint adds the case two distinct reported ports cannot rule out,
     * where both endpoints alias onto a single cache. Neither of those, on its own, separates
     * "reaches its own cache" from "reaches a distinct cache that is not its own": a consistent
     * relabelling would carry both writes and both reads with it and pass.
     *
     * <p>The AUTH is what closes that. Each proxy validates the token of the group it was started
     * for, so a token is the one thing a cache knows about its control-plane identity. An
     * endpoint that reached the other group's proxy would be refused its own group's token, which
     * the last two assertions require to be accepted and the other group's to be refused.
     */
    @Test
    @Order(6)
    void eachAdvertisedEndpointReachesItsOwnCache() throws Exception {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker must be available: this assertion needs a real cache behind each proxy");

        assertEquals("+OK", withAuth(firstPort, FIRST_TOKEN, "SET", "who", "first").trim());
        assertEquals("+OK", withAuth(secondPort, SECOND_TOKEN, "SET", "who", "second").trim());

        assertEquals("first", bulkString(withAuth(firstPort, FIRST_TOKEN, "GET", "who")),
                "The first group's endpoint must reach the first group");
        assertEquals("second", bulkString(withAuth(secondPort, SECOND_TOKEN, "GET", "who")),
                "and the second group's endpoint must reach the second group, not the first");

        // Identity, not just distinctness: each endpoint must refuse the other group's token.
        // On the refusal itself rather than on the helper's prefix: a connection that returned
        // nothing at all would also fail to start with "+OK", and that must not read as a pass.
        assertTrue(withAuth(firstPort, SECOND_TOKEN, "GET", "who").contains(WRONG_TOKEN_ERROR),
                "The first group's endpoint must refuse the second group's token, and say so");
        assertTrue(withAuth(secondPort, FIRST_TOKEN, "GET", "who").contains(WRONG_TOKEN_ERROR),
                "The second group's endpoint must refuse the first group's token, and say so");
    }

    private static ValidatableResponse createGroup(String groupId, int port, String authToken) {
        return given()
                .formParam("Action", "CreateReplicationGroup")
                .formParam("ReplicationGroupId", groupId)
                .formParam("ReplicationGroupDescription", "port sharing test")
                .formParam("Engine", "redis")
                .formParam("NumCacheClusters", "1")
                .formParam("Port", String.valueOf(port))
                .formParam("AuthToken", authToken)
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

    /**
     * AUTHs with {@code token}, then runs one command on the same connection. Returns
     * {@code AUTH-REFUSED} plus the reply when the token is not accepted, which is what an
     * endpoint reaching a different group's proxy produces.
     */
    private static String withAuth(int port, String token, String... command) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(resp("AUTH", token).getBytes(StandardCharsets.UTF_8));
            out.flush();
            String authReply = readReply(in);
            if (!authReply.startsWith("+OK")) {
                return "AUTH-REFUSED: " + authReply.trim();
            }
            out.write(resp(command).getBytes(StandardCharsets.UTF_8));
            out.flush();
            return readReply(in);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        byte[] buffer = new byte[256];
        int read = in.read(buffer);
        return read < 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
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

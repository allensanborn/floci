package io.floci.aws.services.cloudwatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The code, status and message CloudWatch answers its not-found and invalid-input refusals with,
 * on all three wires a client can use: Query (XML), JSON 1.0 and smithy-rpc-v2-cbor, which is
 * what a current botocore speaks for CloudWatch.
 *
 * <p>Where AWS sends no message the member is left out of the body, not written empty or null.
 * botocore reads an absent member as {@code ''}, an empty XML element as {@code None} and a JSON
 * or CBOR null as {@code None}, and the evaluation suite compares the result to {@code ''}.
 */
@QuarkusTest
class CloudWatchErrorMessagesIntegrationTest {

    private static final String CW_SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/monitoring/aws4_request";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** A fresh name per run, so a re-run against a persistent store still means "never created". */
    private static String unique(String prefix) {
        return prefix + System.nanoTime();
    }

    private static RequestSpecification query(String action, Map<String, String> form) {
        RequestSpecification spec = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CW_SCOPE)
                .formParam("Action", action);
        for (Map.Entry<String, String> e : form.entrySet()) {
            spec = spec.formParam(e.getKey(), e.getValue());
        }
        return spec;
    }

    private static ObjectNode body(String... keyValues) {
        ObjectNode node = JSON.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return node;
    }

    private static Map<String, String> form(String... keyValues) {
        Map<String, String> form = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            form.put(keyValues[i], keyValues[i + 1]);
        }
        return form;
    }

    /**
     * The refusal on all three wires. A null {@code message} means the member must be absent from
     * every body, which is how botocore comes to read it as the empty string.
     */
    private static void assertRefusal(String action, Map<String, String> form, int status,
                                      String code, String message) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        form.forEach(request::put);
        assertRefusal(action, form, request, status, code, message);
    }

    /** As above, for a request whose JSON and CBOR body is not the Query form's flat parameters. */
    private static void assertRefusal(String action, Map<String, String> form, ObjectNode request,
                                      int status, String code, String message) throws Exception {

        Response xml = query(action, form).when().post("/");
        assertEquals(status, xml.statusCode(), action + " Query status");
        assertEquals(code, xml.xmlPath().getString("ErrorResponse.Error.Code"), action + " Query code");
        if (message == null) {
            assertFalse(xml.asString().contains("<Message"), action + " Query body: " + xml.asString());
        } else {
            assertEquals(message, xml.xmlPath().getString("ErrorResponse.Error.Message"), action + " Query message");
        }

        Response json = given()
                .contentType("application/x-amz-json-1.0")
                .header("Authorization", CW_SCOPE)
                .header("X-Amz-Target", "GraniteServiceVersion20100801." + action)
                .body(request.toString())
                .when().post("/");
        assertEquals(status, json.statusCode(), action + " JSON status");
        assertMessage(action + " JSON", JSON.readTree(json.asByteArray()), code, message);

        Response cbor = given()
                .contentType("application/cbor")
                .accept("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body(CBOR.writeValueAsBytes(request))
                .when().post("/service/GraniteServiceVersion20100801/operation/" + action);
        assertEquals(status, cbor.statusCode(), action + " CBOR status");
        assertEquals(code + ";Sender", cbor.header("x-amzn-query-error"), action + " CBOR query code");
        assertMessage(action + " CBOR", CBOR.readTree(cbor.asByteArray()), code, message);
    }

    private static void assertMessage(String what, JsonNode error, String code, String message) {
        assertEquals(code, error.path("__type").asText(), what + " __type: " + error);
        if (message == null) {
            assertFalse(error.has("message"), what + " must carry no message member: " + error);
        } else {
            assertEquals(message, error.path("message").asText(), what + " message");
        }
    }

    /** SetAlarmState's ResourceNotFound carries no message, and the missing alarm is not created. */
    @Test
    void setAlarmStateOnAMissingAlarmAnswersNotFoundWithNoMessage() throws Exception {
        String alarm = unique("no-such-alarm-");
        assertRefusal("SetAlarmState", form("AlarmName", alarm, "StateValue", "OK", "StateReason", "test"),
                404, "ResourceNotFound", null);

        // The refusal left nothing behind: a separate read finds no alarm of that name.
        String described = query("DescribeAlarms", form("AlarmNames.member.1", alarm)).when().post("/").asString();
        assertFalse(described.contains(alarm), described);
    }

    /**
     * Tag, Untag and ListTagsForResource answer an ARN naming nothing with ResourceNotFoundException
     * and no message, whichever kind of resource the ARN names. Each kind is created first and its
     * real ARN accepted, so a malformed ARN cannot be what the refusal is about.
     */
    @Test
    void tagOperationsOnAMissingResourceAnswerNotFoundWithNoMessage() throws Exception {
        String dashboard = unique("tag-dash-");
        String stream = unique("tag-stream-");
        String alarm = unique("tag-alarm-");
        query("PutDashboard", form("DashboardName", dashboard, "DashboardBody", "{\"widgets\":[]}"))
                .when().post("/").then().statusCode(200);
        query("PutMetricStream", form("Name", stream, "FirehoseArn", "arn:aws:firehose:us-east-1:000000000000:deliverystream/m",
                "RoleArn", "arn:aws:iam::000000000000:role/r", "OutputFormat", "json")).when().post("/").then().statusCode(200);
        query("PutMetricAlarm", form("AlarmName", alarm, "MetricName", "CPU", "Namespace", "AWS/EC2",
                "ComparisonOperator", "GreaterThanThreshold", "EvaluationPeriods", "1", "Period", "60",
                "Statistic", "Average", "Threshold", "80")).when().post("/").then().statusCode(200);

        String prefix = "arn:aws:cloudwatch:us-east-1:000000000000:";
        String[][] kinds = {
                {prefix + "dashboard/" + dashboard, prefix + "dashboard/" + dashboard + "-gone"},
                {prefix + "metric-stream/" + stream, prefix + "metric-stream/" + stream + "-gone"},
                {prefix + "alarm:" + alarm, prefix + "alarm:" + alarm + "-gone"}};
        for (String[] arns : kinds) {
            // The real ARN resolves, read back through a separate call.
            query("TagResource", form("ResourceARN", arns[0], "Tags.member.1.Key", "k", "Tags.member.1.Value", "v"))
                    .when().post("/").then().statusCode(200);
            String tags = query("ListTagsForResource", form("ResourceARN", arns[0])).when().post("/").asString();
            assertTrue(tags.contains("<Key>k</Key>"), tags);

            ObjectNode tag = body("ResourceARN", arns[1]);
            tag.putArray("Tags").addObject().put("Key", "k").put("Value", "v");
            assertRefusal("TagResource", form("ResourceARN", arns[1], "Tags.member.1.Key", "k",
                    "Tags.member.1.Value", "v"), tag, 404, "ResourceNotFoundException", null);
            ObjectNode untag = body("ResourceARN", arns[1]);
            untag.putArray("TagKeys").add("k");
            assertRefusal("UntagResource", form("ResourceARN", arns[1], "TagKeys.member.1", "k"),
                    untag, 404, "ResourceNotFoundException", null);
            assertRefusal("ListTagsForResource", form("ResourceARN", arns[1]),
                    404, "ResourceNotFoundException", null);
        }
    }

    /** GetDashboard names the dashboard in its ResourceNotFound message; a deleted one reads the same. */
    @Test
    void getDashboardOnAMissingDashboardNamesItInTheMessage() throws Exception {
        String name = unique("no-such-dashboard-");
        assertRefusal("GetDashboard", form("DashboardName", name), 404, "ResourceNotFound",
                "Dashboard " + name + " does not exist");

        String present = unique("present-dashboard-");
        query("PutDashboard", form("DashboardName", present, "DashboardBody", "{\"widgets\":[]}"))
                .when().post("/").then().statusCode(200);
        query("GetDashboard", form("DashboardName", present)).when().post("/").then().statusCode(200);
        query("DeleteDashboards", form("DashboardNames.member.1", present)).when().post("/").then().statusCode(200);
        assertRefusal("GetDashboard", form("DashboardName", present), 404, "ResourceNotFound",
                "Dashboard " + present + " does not exist");
    }

    /** GetMetricStream's ResourceNotFoundException carries no message. */
    @Test
    void getMetricStreamOnAMissingStreamAnswersNotFoundWithNoMessage() throws Exception {
        assertRefusal("GetMetricStream", form("Name", unique("no-such-stream-")),
                404, "ResourceNotFoundException", null);
    }
}

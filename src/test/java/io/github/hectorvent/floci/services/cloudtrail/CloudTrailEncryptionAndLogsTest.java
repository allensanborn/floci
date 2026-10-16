package io.github.hectorvent.floci.services.cloudtrail;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * KmsKeyId and CloudWatch Logs delivery round-trip through CreateTrail, DescribeTrails and
 * UpdateTrail. Terraform's aws_cloudtrail reads them back on every refresh, so a dropped value is
 * a perpetual diff.
 */
@QuarkusTest
class CloudTrailEncryptionAndLogsTest {

    private static final String CT_TARGET = "CloudTrail_20131101.";
    private static final String JSON11 = "application/x-amz-json-1.1";
    private static final String KEY = "arn:aws:kms:us-east-1:000000000000:key/0f1e2d3c-4b5a-6978-8091-a2b3c4d5e6f7";
    private static final String GROUP = "arn:aws:logs:us-east-1:000000000000:log-group:/trail:*";
    private static final String ROLE = "arn:aws:iam::000000000000:role/trail-to-logs";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given().header("X-Amz-Target", CT_TARGET + action).contentType(JSON11).body(body)
                .when().post("/").then();
    }

    @Test
    void encryptionAndCloudWatchLogsRoundTrip() {
        call("CreateTrail", "{\"Name\":\"enc-trail\",\"S3BucketName\":\"b\",\"KmsKeyId\":\"" + KEY
                + "\",\"CloudWatchLogsLogGroupArn\":\"" + GROUP + "\",\"CloudWatchLogsRoleArn\":\"" + ROLE + "\"}")
                .statusCode(200)
                .body("KmsKeyId", equalTo(KEY))
                .body("CloudWatchLogsLogGroupArn", equalTo(GROUP));

        // Selectors rebuild the stored trail; the settings must survive that.
        call("PutEventSelectors", "{\"TrailName\":\"enc-trail\",\"EventSelectors\":[{\"ReadWriteType\":\"All\","
                + "\"IncludeManagementEvents\":true}]}").statusCode(200);
        call("DescribeTrails", "{\"trailNameList\":[\"enc-trail\"]}")
                .statusCode(200)
                .body("trailList[0].KmsKeyId", equalTo(KEY))
                .body("trailList[0].CloudWatchLogsLogGroupArn", equalTo(GROUP))
                .body("trailList[0].CloudWatchLogsRoleArn", equalTo(ROLE));

        call("UpdateTrail", "{\"Name\":\"enc-trail\",\"KmsKeyId\":\"\"}")
                .statusCode(200)
                .body("KmsKeyId", nullValue())
                .body("CloudWatchLogsRoleArn", equalTo(ROLE));
    }

    @Test
    void cloudWatchLogsNeedsBothTheGroupAndTheRole() {
        call("CreateTrail", "{\"Name\":\"half-trail\",\"S3BucketName\":\"b\",\"CloudWatchLogsLogGroupArn\":\""
                + GROUP + "\"}")
                .statusCode(400)
                .body(containsString("InvalidCloudWatchLogsRoleArnException"));
    }
}

package io.floci.aws.services.glue;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The code, message and status Glue answers with for a missing resource, a duplicate and a
 * refused input. AWS words these per operation and per resource; each expectation below is the
 * message an AWS-verified behavioural check asserts, so a "tidier" shared wording fails here.
 */
@QuarkusTest
class GlueErrorMessagesIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/my-role";
    private static final String NOT_FOUND = "EntityNotFoundException";
    private static final String EXISTS = "AlreadyExistsException";
    private static final String INVALID = "InvalidInputException";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void ok(String target, String body) {
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "AWSGlue." + target).body(body)
                .when().post("/").then().statusCode(200);
    }

    private static void fails(String target, String body, String code, String message) {
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "AWSGlue." + target).body(body)
                .when().post("/")
                .then()
                .statusCode(400)
                .body("__type", equalTo(code))
                .body("message", equalTo(message));
    }

    private static String createDatabase() {
        String db = unique("err-db");
        ok("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + db + "\"}}");
        return db;
    }

    private static String createTable(String db) {
        String table = unique("err-tbl");
        ok("CreateTable", "{\"DatabaseName\":\"" + db + "\",\"TableInput\":{\"Name\":\"" + table
                + "\",\"PartitionKeys\":[{\"Name\":\"year\",\"Type\":\"string\"}]}}");
        return table;
    }

    private static String createJob() {
        String job = unique("err-job");
        ok("CreateJob", "{\"Name\":\"" + job + "\",\"Role\":\"" + ROLE + "\",\"Command\":{}}");
        return job;
    }

    @Test
    void databaseErrors() {
        String db = createDatabase();
        fails("CreateDatabase", "{\"DatabaseInput\":{\"Name\":\"" + db + "\"}}", EXISTS, "Database already exists.");

        ok("DeleteDatabase", "{\"Name\":\"" + db + "\"}");
        String missing = "Database " + db + " not found.";
        fails("GetDatabase", "{\"Name\":\"" + db + "\"}", NOT_FOUND, missing);
        fails("UpdateDatabase", "{\"Name\":\"" + db + "\",\"DatabaseInput\":{\"Name\":\"" + db + "\"}}",
                NOT_FOUND, missing);
        fails("DeleteDatabase", "{\"Name\":\"" + db + "\"}", NOT_FOUND, missing);
        fails("CreateTable", "{\"DatabaseName\":\"" + db + "\",\"TableInput\":{\"Name\":\"t\"}}", NOT_FOUND, missing);
    }

    @Test
    void tableErrors() {
        String db = createDatabase();
        String table = createTable(db);
        fails("CreateTable", "{\"DatabaseName\":\"" + db + "\",\"TableInput\":{\"Name\":\"" + table + "\"}}",
                EXISTS, "Table already exists.");

        ok("DeleteTable", "{\"DatabaseName\":\"" + db + "\",\"Name\":\"" + table + "\"}");
        String named = "Table " + table + " not found.";
        // GetTable's miss is a constant; the other operations name the table.
        fails("GetTable", "{\"DatabaseName\":\"" + db + "\",\"Name\":\"" + table + "\"}", NOT_FOUND, "Entity Not Found");
        fails("GetTable", "{\"DatabaseName\":\"" + db + "-gone\",\"Name\":\"x\"}", NOT_FOUND, "Entity Not Found");
        fails("UpdateTable", "{\"DatabaseName\":\"" + db + "\",\"TableInput\":{\"Name\":\"" + table + "\"}}",
                NOT_FOUND, named);
        fails("DeleteTable", "{\"DatabaseName\":\"" + db + "\",\"Name\":\"" + table + "\"}", NOT_FOUND, named);
        fails("GetPartitionIndexes", "{\"DatabaseName\":\"" + db + "\",\"TableName\":\"" + table + "\"}",
                NOT_FOUND, named);
    }

    @Test
    void tableVersionErrors() {
        String db = createDatabase();
        String table = createTable(db);
        for (int i = 0; i < 2; i++) {
            ok("UpdateTable", "{\"DatabaseName\":\"" + db + "\",\"TableInput\":{\"Name\":\"" + table + "\"}}");
        }
        String key = "\"DatabaseName\":\"" + db + "\",\"TableName\":\"" + table + "\"";
        // Versions are 0 (archived), 1 (archived) and 2 (current).
        fails("DeleteTableVersion", "{" + key + ",\"VersionId\":\"2\"}", INVALID,
                "VersionId cannot be equal to the latest.");
        ok("DeleteTableVersion", "{" + key + ",\"VersionId\":\"0\"}");
        fails("GetTableVersion", "{" + key + ",\"VersionId\":\"0\"}", NOT_FOUND, "Table version not found.");
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "AWSGlue.BatchDeleteTableVersion")
                .body("{" + key + ",\"VersionIds\":[\"9999\"]}")
                .when().post("/")
                .then().statusCode(200)
                .body("Errors[0].ErrorDetail.ErrorCode", equalTo(NOT_FOUND))
                .body("Errors[0].ErrorDetail.ErrorMessage", equalTo("Version not found."));
    }

    @Test
    void partitionErrors() {
        String db = createDatabase();
        String table = createTable(db);
        String key = "\"DatabaseName\":\"" + db + "\",\"TableName\":\"" + table + "\"";
        fails("GetPartition", "{" + key + ",\"PartitionValues\":[\"2023\"]}", NOT_FOUND, "Cannot find partition.");
        fails("UpdatePartition", "{" + key + ",\"PartitionValueList\":[\"2023\"],"
                + "\"PartitionInput\":{\"Values\":[\"2023\"]}}", NOT_FOUND, "Partition [2023] not found");
    }

    @Test
    void jobErrors() {
        String job = unique("err-missing-job");
        fails("GetJob", "{\"JobName\":\"" + job + "\"}", NOT_FOUND, "Job not found.");
        fails("UpdateJob", "{\"JobName\":\"" + job + "\",\"JobUpdate\":{\"Role\":\"" + ROLE + "\"}}",
                NOT_FOUND, "Job not found.");
        fails("GetJobRuns", "{\"JobName\":\"" + job + "\"}", NOT_FOUND, "Job not found.");
        fails("StartJobRun", "{\"JobName\":\"" + job + "\"}", NOT_FOUND,
                "Failed to start job run due to missing metadata.");

        String real = createJob();
        fails("GetJobRun", "{\"JobName\":\"" + real + "\",\"RunId\":\"jr_nope\"}", NOT_FOUND,
                "Job run not found for the given job name and runId.");
    }

    @Test
    void classifierErrors() {
        String name = unique("err-classifier");
        String doubled = "Classifier " + name + " not found. not found.";
        fails("GetClassifier", "{\"Name\":\"" + name + "\"}", NOT_FOUND, doubled);
        fails("UpdateClassifier", "{\"CsvClassifier\":{\"Name\":\"" + name + "\",\"Delimiter\":\";\"}}",
                NOT_FOUND, doubled);
    }

    @Test
    void crawlerErrors() {
        String name = unique("err-crawler");
        String missing = "Crawler entry with name " + name + " does not exist";
        fails("GetCrawler", "{\"Name\":\"" + name + "\"}", NOT_FOUND, missing);
        fails("DeleteCrawler", "{\"Name\":\"" + name + "\"}", NOT_FOUND, missing);
        fails("CreateCrawler", "{\"Name\":\"" + name + "\",\"Role\":\"" + ROLE + "\",\"Targets\":{}}",
                INVALID, "Targets are not defined");
    }

    @Test
    void connectionErrors() {
        String name = unique("err-conn");
        String input = "{\"Name\":\"" + name + "\",\"ConnectionType\":\"JDBC\",\"ConnectionProperties\":"
                + "{\"JDBC_CONNECTION_URL\":\"jdbc:mysql://example.com:3306/db\",\"USERNAME\":\"a\",\"PASSWORD\":\"b\"}}";
        ok("CreateConnection", "{\"ConnectionInput\":" + input + "}");
        ok("DeleteConnection", "{\"ConnectionName\":\"" + name + "\"}");

        fails("GetConnection", "{\"Name\":\"" + name + "\"}", NOT_FOUND,
                "Connection with name " + name + " in account " + ACCOUNT + " is not found");
        fails("UpdateConnection", "{\"Name\":\"" + name + "\",\"ConnectionInput\":" + input + "}", NOT_FOUND,
                "Connection not found: " + name);
        fails("DeleteConnection", "{\"ConnectionName\":\"" + name + "\"}", NOT_FOUND, "Connection not found");
        given().contentType(CONTENT_TYPE).header("X-Amz-Target", "AWSGlue.BatchDeleteConnection")
                .body("{\"ConnectionNameList\":[\"" + name + "\"]}")
                .when().post("/")
                .then().statusCode(200)
                .body("Errors." + name + ".ErrorCode", equalTo(NOT_FOUND))
                .body("Errors." + name + ".ErrorMessage", equalTo("Connection not found: " + name));
    }

    @Test
    void triggerErrors() {
        String job = createJob();
        String name = unique("err-trigger");
        fails("GetTrigger", "{\"Name\":\"" + name + "\"}", NOT_FOUND, "Trigger not found.");
        fails("StartTrigger", "{\"Name\":\"" + name + "\"}", NOT_FOUND, "Trigger not found.");
        fails("CreateTrigger", "{\"Name\":\"" + name + "\",\"Type\":\"CONDITIONAL\",\"Actions\":[{\"JobName\":\""
                + job + "\"}]}", INVALID, "Predicate cannot be null or empty");
        fails("CreateTrigger", "{\"Name\":\"" + name + "\",\"Type\":\"SCHEDULED\",\"Schedule\":\"every-so-often\","
                + "\"Actions\":[{\"JobName\":\"" + job + "\"}]}", INVALID, "The schedule every-so-often is invalid.");
        fails("GetTrigger", "{\"Name\":\"" + name + "\"}", NOT_FOUND, "Trigger not found.");
    }

    @Test
    void dataQualityRulesetAndSecurityConfigurationAndTagErrors() {
        String ruleset = unique("err-dqr");
        String missing = "Cannot find Data Quality Ruleset in account " + ACCOUNT + " with name " + ruleset;
        fails("GetDataQualityRuleset", "{\"Name\":\"" + ruleset + "\"}", NOT_FOUND, missing);
        fails("UpdateDataQualityRuleset", "{\"Name\":\"" + ruleset + "\",\"Description\":\"v2\"}", NOT_FOUND, missing);

        fails("GetSecurityConfiguration", "{\"Name\":\"" + unique("err-sec") + "\"}", NOT_FOUND, "");
        fails("TagResource", "{\"ResourceArn\":\"not-a-valid-arn\",\"TagsToAdd\":{\"k\":\"v\"}}", INVALID, "");
    }
}

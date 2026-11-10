package io.floci.aws.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/** Verifies field-level encryption configs and profiles return the full stored configuration. */
@QuarkusTest
class CloudFrontFieldLevelEncryptionIntegrationTest {

    private static final String API = "/2020-05-31/";

    private static String configBody(String callerReference, String comment, String queryProfileId) {
        return """
                <FieldLevelEncryptionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>%s</CallerReference>
                  <Comment>%s</Comment>
                  <QueryArgProfileConfig>
                    <ForwardWhenQueryArgProfileIsUnknown>true</ForwardWhenQueryArgProfileIsUnknown>
                    <QueryArgProfiles>
                      <Quantity>1</Quantity>
                      <Items><QueryArgProfile><QueryArg>secret</QueryArg><ProfileId>%s</ProfileId></QueryArgProfile></Items>
                    </QueryArgProfiles>
                  </QueryArgProfileConfig>
                  <ContentTypeProfileConfig>
                    <ForwardWhenContentTypeIsUnknown>false</ForwardWhenContentTypeIsUnknown>
                    <ContentTypeProfiles>
                      <Quantity>1</Quantity>
                      <Items><ContentTypeProfile>
                        <Format>URLEncoded</Format><ProfileId>%s</ProfileId><ContentType>application/x-www-form-urlencoded</ContentType>
                      </ContentTypeProfile></Items>
                    </ContentTypeProfiles>
                  </ContentTypeProfileConfig>
                </FieldLevelEncryptionConfig>
                """.formatted(callerReference, comment, queryProfileId, queryProfileId);
    }

    private static String profileBody(String name, String callerReference, String comment) {
        return """
                <FieldLevelEncryptionProfileConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                  <Comment>%s</Comment>
                  <EncryptionEntities>
                    <Quantity>1</Quantity>
                    <Items><EncryptionEntity>
                      <PublicKeyId>K2ABCDEFGHIJKL</PublicKeyId>
                      <ProviderId>provider-1</ProviderId>
                      <FieldPatterns><Quantity>2</Quantity><Items><FieldPattern>card</FieldPattern><FieldPattern>ssn*</FieldPattern></Items></FieldPatterns>
                    </EncryptionEntity></Items>
                  </EncryptionEntities>
                </FieldLevelEncryptionProfileConfig>
                """.formatted(name, callerReference, comment);
    }

    private static Response post(String path, String body) {
        return given().contentType("application/xml").body(body).post(API + path);
    }

    @Test
    void configRoundTripsThroughEveryReadOperation() {
        String ref = UUID.randomUUID().toString();
        Response created = post("field-level-encryption", configBody(ref, "first", "P1"));
        created.then().statusCode(201)
                .body("FieldLevelEncryption.FieldLevelEncryptionConfig.QueryArgProfileConfig"
                        + ".QueryArgProfiles.Items.QueryArgProfile.ProfileId", equalTo("P1"));
        String id = created.xmlPath().getString("FieldLevelEncryption.Id");
        String etag = created.header("ETag");

        given().get(API + "field-level-encryption/" + id)
                .then().statusCode(200)
                .header("ETag", equalTo(etag))
                .body("FieldLevelEncryption.Id", equalTo(id))
                .body("FieldLevelEncryption.FieldLevelEncryptionConfig.CallerReference", equalTo(ref))
                .body("FieldLevelEncryption.FieldLevelEncryptionConfig.Comment", equalTo("first"))
                .body("FieldLevelEncryption.FieldLevelEncryptionConfig.ContentTypeProfileConfig"
                        + ".ContentTypeProfiles.Items.ContentTypeProfile.Format", equalTo("URLEncoded"));

        given().get(API + "field-level-encryption/" + id + "/config")
                .then().statusCode(200)
                .header("ETag", equalTo(etag))
                .body("FieldLevelEncryptionConfig.CallerReference", equalTo(ref))
                .body("FieldLevelEncryptionConfig.Comment", equalTo("first"))
                .body("FieldLevelEncryptionConfig.QueryArgProfileConfig.ForwardWhenQueryArgProfileIsUnknown",
                        equalTo("true"))
                .body("FieldLevelEncryptionConfig.QueryArgProfileConfig.QueryArgProfiles.Quantity",
                        equalTo("1"))
                .body("FieldLevelEncryptionConfig.QueryArgProfileConfig.QueryArgProfiles.Items"
                        + ".QueryArgProfile.QueryArg", equalTo("secret"))
                .body("FieldLevelEncryptionConfig.ContentTypeProfileConfig.ContentTypeProfiles.Items"
                        + ".ContentTypeProfile.ProfileId", equalTo("P1"));
    }

    @Test
    void replayedConfigDoesNotCarryTheRequestsNamespaceOrWhitespace() {
        String created = post("field-level-encryption", configBody(UUID.randomUUID().toString(), "ws", "P1"))
                .asString();
        String id = created.replaceAll("(?s).*<Id>([^<]+)</Id>.*", "$1");

        for (String doc : new String[] {created, given().get(API + "field-level-encryption/" + id).asString()}) {
            org.junit.jupiter.api.Assertions.assertEquals(1, doc.split("xmlns", -1).length - 1, doc);
            org.junit.jupiter.api.Assertions.assertFalse(doc.contains("<?xml"), doc);
            org.junit.jupiter.api.Assertions.assertFalse(doc.matches("(?s).*>\\s+<.*"), doc);
        }
        String config = given().get(API + "field-level-encryption/" + id + "/config").asString();
        org.junit.jupiter.api.Assertions.assertFalse(config.contains("xmlns"), config);
        org.junit.jupiter.api.Assertions.assertFalse(config.matches("(?s).*>\\s+<.*"), config);
    }

    @Test
    void updateReplacesTheStoredConfigAndChecksIfMatch() {
        String ref = UUID.randomUUID().toString();
        Response created = post("field-level-encryption", configBody(ref, "before", "P1"));
        String id = created.xmlPath().getString("FieldLevelEncryption.Id");
        String etag = created.header("ETag");

        given().contentType("application/xml").header("If-Match", "stale")
                .body(configBody(ref, "after", "P2"))
                .put(API + "field-level-encryption/" + id + "/config")
                .then().statusCode(400);

        Response updated = given().contentType("application/xml").header("If-Match", etag)
                .body(configBody(ref, "after", "P2"))
                .put(API + "field-level-encryption/" + id + "/config");
        updated.then().statusCode(200)
                .body("FieldLevelEncryption.FieldLevelEncryptionConfig.Comment", equalTo("after"));

        given().get(API + "field-level-encryption/" + id + "/config")
                .then().statusCode(200)
                .header("ETag", not(equalTo(etag)))
                .body("FieldLevelEncryptionConfig.Comment", equalTo("after"))
                .body("FieldLevelEncryptionConfig.QueryArgProfileConfig.QueryArgProfiles.Items"
                        + ".QueryArgProfile.ProfileId", equalTo("P2"));
    }

    @Test
    void listReturnsModeledSummariesWithTheProfileConfigs() {
        String ref = UUID.randomUUID().toString();
        String id = post("field-level-encryption", configBody(ref, "listed", "P3"))
                .xmlPath().getString("FieldLevelEncryption.Id");

        Response list = given().get(API + "field-level-encryption");
        list.then().statusCode(200)
                .body("FieldLevelEncryptionList.Items.FieldLevelEncryptionSummary.Id", hasItem(id))
                .body("FieldLevelEncryptionList.Items.FieldLevelEncryptionSummary.find { it.Id == '" + id
                        + "' }.QueryArgProfileConfig.QueryArgProfiles.Items.QueryArgProfile.ProfileId",
                        equalTo("P3"))
                .body("FieldLevelEncryptionList.Items.FieldLevelEncryptionSummary.find { it.Id == '" + id
                        + "' }.Comment", equalTo("listed"));
        // the summary shape has no CallerReference
        org.junit.jupiter.api.Assertions.assertFalse(
                list.xmlPath().getString("FieldLevelEncryptionList.Items.FieldLevelEncryptionSummary"
                        + ".find { it.Id == '" + id + "' }.CallerReference").length() > 0);
    }

    @Test
    void profileRoundTripsEncryptionEntities() {
        String ref = UUID.randomUUID().toString();
        String name = "profile-" + ref;
        Response created = post("field-level-encryption-profile", profileBody(name, ref, "pc"));
        created.then().statusCode(201)
                .body("FieldLevelEncryptionProfile.FieldLevelEncryptionProfileConfig.EncryptionEntities"
                        + ".Items.EncryptionEntity.ProviderId", equalTo("provider-1"));
        String id = created.xmlPath().getString("FieldLevelEncryptionProfile.Id");
        String etag = created.header("ETag");

        given().get(API + "field-level-encryption-profile/" + id)
                .then().statusCode(200)
                .header("ETag", equalTo(etag))
                .body("FieldLevelEncryptionProfile.FieldLevelEncryptionProfileConfig.Name", equalTo(name))
                .body("FieldLevelEncryptionProfile.FieldLevelEncryptionProfileConfig.EncryptionEntities"
                        + ".Items.EncryptionEntity.PublicKeyId", equalTo("K2ABCDEFGHIJKL"))
                .body("FieldLevelEncryptionProfile.FieldLevelEncryptionProfileConfig.EncryptionEntities"
                        + ".Items.EncryptionEntity.FieldPatterns.Items.FieldPattern",
                        org.hamcrest.Matchers.contains("card", "ssn*"));

        given().get(API + "field-level-encryption-profile/" + id + "/config")
                .then().statusCode(200)
                .header("ETag", equalTo(etag))
                .body("FieldLevelEncryptionProfileConfig.Comment", equalTo("pc"))
                .body("FieldLevelEncryptionProfileConfig.EncryptionEntities.Quantity", equalTo("1"));

        given().contentType("application/xml").header("If-Match", etag)
                .body(profileBody(name, ref, "changed"))
                .put(API + "field-level-encryption-profile/" + id + "/config")
                .then().statusCode(200);
        given().get(API + "field-level-encryption-profile/" + id + "/config")
                .then().statusCode(200)
                .body("FieldLevelEncryptionProfileConfig.Comment", equalTo("changed"));

        given().get(API + "field-level-encryption-profile")
                .then().statusCode(200)
                .body("FieldLevelEncryptionProfileList.Items.FieldLevelEncryptionProfileSummary.Id",
                        hasItem(id))
                .body("FieldLevelEncryptionProfileList.Items.FieldLevelEncryptionProfileSummary.find { it.Id == '"
                        + id + "' }.EncryptionEntities.Items.EncryptionEntity.ProviderId",
                        equalTo("provider-1"));
    }

    @Test
    void unknownIdsReturnNotFound() {
        given().get(API + "field-level-encryption/nope").then().statusCode(404)
                .body(containsString("NoSuchFieldLevelEncryptionConfig"));
        given().get(API + "field-level-encryption-profile/nope/config").then().statusCode(404)
                .body(containsString("NoSuchFieldLevelEncryptionProfile"));
    }
}

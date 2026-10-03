package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IAM SSH public keys (Upload/Get/Update/List/DeleteSSHPublicKey) through the Query protocol,
 * the operations behind Terraform's {@code aws_iam_user_ssh_key}. Ordered: one user's key
 * moves through upload, read back, status change, rename, delete.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamSshPublicKeyIntegrationTest {

    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String USER = "ssh-key-user";
    private static final String RENAMED = "ssh-key-user-renamed";

    private static RSAPublicKey rsaKey;
    private static String sshBody;
    private static String expectedFingerprint;
    private static String keyId;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
        byte[] blob = sshRsaBlob(rsaKey);
        sshBody = "ssh-rsa " + Base64.getEncoder().encodeToString(blob);
        // OpenSSH's MD5 fingerprint (ssh-keygen -l -E md5): MD5 of the decoded key blob.
        expectedFingerprint = HexFormat.of().withDelimiter(":")
                .formatHex(MessageDigest.getInstance("MD5").digest(blob));
    }

    private static byte[] sshRsaBlob(RSAPublicKey key) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (byte[] field : new byte[][]{"ssh-rsa".getBytes(), key.getPublicExponent().toByteArray(),
                key.getModulus().toByteArray()}) {
            out.writeInt(field.length);
            out.write(field);
        }
        return bytes.toByteArray();
    }

    private static String newSshBody() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return "ssh-rsa " + Base64.getEncoder().encodeToString(
                sshRsaBlob((RSAPublicKey) generator.generateKeyPair().getPublic()));
    }

    private static ValidatableResponse call(String action, String... params) {
        return callAs(IAM_CREDENTIAL, action, params);
    }

    private static ValidatableResponse callAs(String authorization, String action, String... params) {
        RequestSpecification request = given()
                .formParam("Action", action)
                .header("Authorization", authorization);
        for (int i = 0; i < params.length; i += 2) {
            request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    @Test
    @Order(1)
    void uploadReturnsIdFingerprintAndActiveStatus() {
        call("CreateUser", "UserName", USER).statusCode(200);
        String prefix = "UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.";
        keyId = call("UploadSSHPublicKey", "UserName", USER, "SSHPublicKeyBody", sshBody + " me@host")
                .statusCode(200)
                .body(prefix + "UserName", equalTo(USER))
                .body(prefix + "SSHPublicKeyId", matchesPattern("APKA[A-Z0-9]{16}"))
                .body(prefix + "Fingerprint", equalTo(expectedFingerprint))
                .body(prefix + "Status", equalTo("Active"))
                .body(prefix + "UploadDate", notNullValue())
                .extract().path(prefix + "SSHPublicKeyId");
    }

    @Test
    @Order(2)
    void getWithSshEncodingReturnsTheUploadedKey() {
        String prefix = "GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey.";
        call("GetSSHPublicKey", "UserName", USER, "SSHPublicKeyId", keyId, "Encoding", "SSH")
                .statusCode(200)
                .body(prefix + "SSHPublicKeyBody", startsWith(sshBody))
                .body(prefix + "Fingerprint", equalTo(expectedFingerprint))
                .body(prefix + "Status", equalTo("Active"));
    }

    @Test
    @Order(3)
    void getWithPemEncodingReturnsTheSameRsaKey() throws Exception {
        String pem = call("GetSSHPublicKey", "UserName", USER, "SSHPublicKeyId", keyId, "Encoding", "PEM")
                .statusCode(200)
                .extract().path("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey.SSHPublicKeyBody");
        String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        RSAPublicKey parsed = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        assertEquals(rsaKey.getModulus(), parsed.getModulus());
        assertEquals(rsaKey.getPublicExponent(), parsed.getPublicExponent());
    }

    @Test
    @Order(4)
    void uploadingTheSameKeyAgainIsDuplicate() {
        call("UploadSSHPublicKey", "UserName", USER, "SSHPublicKeyBody", sshBody)
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DuplicateSSHPublicKey"));
    }

    @Test
    @Order(5)
    void uploadRejectsMalformedAndShortKeys() throws Exception {
        call("UploadSSHPublicKey", "UserName", USER, "SSHPublicKeyBody", "ssh-rsa not-base64!")
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidPublicKey"));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String shortKey = "ssh-rsa " + Base64.getEncoder().encodeToString(
                sshRsaBlob((RSAPublicKey) generator.generateKeyPair().getPublic()));
        call("UploadSSHPublicKey", "UserName", USER, "SSHPublicKeyBody", shortKey)
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidPublicKey"));
    }

    @Test
    @Order(6)
    void updateStatusIsVisibleInList() {
        call("UpdateSSHPublicKey", "UserName", USER, "SSHPublicKeyId", keyId, "Status", "Inactive")
                .statusCode(200);
        String member = "ListSSHPublicKeysResponse.ListSSHPublicKeysResult.SSHPublicKeys.member.";
        call("ListSSHPublicKeys", "UserName", USER)
                .statusCode(200)
                .body(member + "SSHPublicKeyId", equalTo(keyId))
                .body(member + "Status", equalTo("Inactive"))
                .body(member + "UploadDate", notNullValue());
    }

    @Test
    @Order(7)
    void deleteUserIsBlockedWhileAKeyExists() {
        call("DeleteUser", "UserName", USER)
                .statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"));
    }

    @Test
    @Order(8)
    void renameCarriesTheKey() {
        call("UpdateUser", "UserName", USER, "NewUserName", RENAMED).statusCode(200);
        call("GetSSHPublicKey", "UserName", RENAMED, "SSHPublicKeyId", keyId, "Encoding", "SSH")
                .statusCode(200)
                .body("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey.UserName", equalTo(RENAMED));
    }

    @Test
    @Order(9)
    void deleteRemovesTheKeyAndUnblocksDeleteUser() {
        call("DeleteSSHPublicKey", "UserName", RENAMED, "SSHPublicKeyId", keyId).statusCode(200);
        call("GetSSHPublicKey", "UserName", RENAMED, "SSHPublicKeyId", keyId, "Encoding", "SSH")
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
        String list = call("ListSSHPublicKeys", "UserName", RENAMED).statusCode(200).extract().asString();
        assertEquals("", new XmlPath(list).getString(
                "ListSSHPublicKeysResponse.ListSSHPublicKeysResult.SSHPublicKeys"));
        call("DeleteUser", "UserName", RENAMED).statusCode(200);
    }

    @Test
    @Order(10)
    void pemUploadIsReadBackAsSsh() {
        call("CreateUser", "UserName", USER).statusCode(200);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(rsaKey.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        String id = call("UploadSSHPublicKey", "UserName", USER, "SSHPublicKeyBody", pem)
                .statusCode(200)
                .body("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.Fingerprint",
                        equalTo(expectedFingerprint))
                .extract().path("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.SSHPublicKeyId");
        call("GetSSHPublicKey", "UserName", USER, "SSHPublicKeyId", id, "Encoding", "SSH")
                .statusCode(200)
                .body("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey.SSHPublicKeyBody",
                        equalTo(sshBody));
        call("DeleteSSHPublicKey", "UserName", USER, "SSHPublicKeyId", id).statusCode(200);
        call("DeleteUser", "UserName", USER).statusCode(200);
    }

    // ---- Error paths, on a second user so the ordered flow above is undisturbed. ----

    private static final String LIMIT_USER = "ssh-key-limit-user";
    private static final String MEMBERS = "ListSSHPublicKeysResponse.ListSSHPublicKeysResult.SSHPublicKeys.member";
    private static String limitKeyId;

    private static String getStatus(String user, String id) {
        return call("GetSSHPublicKey", "UserName", user, "SSHPublicKeyId", id, "Encoding", "SSH")
                .statusCode(200)
                .extract().path("GetSSHPublicKeyResponse.GetSSHPublicKeyResult.SSHPublicKey.Status");
    }

    @Test
    @Order(11)
    void sixthKeyIsLimitExceededAndNotStored() throws Exception {
        call("CreateUser", "UserName", LIMIT_USER).statusCode(200);
        for (int i = 0; i < 5; i++) {
            String id = call("UploadSSHPublicKey", "UserName", LIMIT_USER, "SSHPublicKeyBody", newSshBody())
                    .statusCode(200)
                    .extract().path("UploadSSHPublicKeyResponse.UploadSSHPublicKeyResult.SSHPublicKey.SSHPublicKeyId");
            if (i == 0) {
                limitKeyId = id;
            }
        }
        call("UploadSSHPublicKey", "UserName", LIMIT_USER, "SSHPublicKeyBody", newSshBody())
                .statusCode(409)
                .body("ErrorResponse.Error.Code", equalTo("LimitExceeded"));
        call("ListSSHPublicKeys", "UserName", LIMIT_USER)
                .statusCode(200)
                .body(MEMBERS + ".size()", equalTo(5));
    }

    @Test
    @Order(12)
    void unknownEncodingIsUnrecognizedPublicKeyEncoding() {
        call("GetSSHPublicKey", "UserName", LIMIT_USER, "SSHPublicKeyId", limitKeyId, "Encoding", "DER")
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("UnrecognizedPublicKeyEncoding"));
    }

    @Test
    @Order(13)
    void invalidStatusIsInvalidInputAndLeavesStatusUnchanged() {
        call("UpdateSSHPublicKey", "UserName", LIMIT_USER, "SSHPublicKeyId", limitKeyId, "Status", "Disabled")
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInput"));
        assertEquals("Active", getStatus(LIMIT_USER, limitKeyId));
    }

    @Test
    @Order(14)
    void listWithoutUserNameUsesTheCallersUser() {
        String accessKeyId = call("CreateAccessKey", "UserName", LIMIT_USER)
                .statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        String auth = "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260227/us-east-1/iam/aws4_request";
        callAs(auth, "ListSSHPublicKeys")
                .statusCode(200)
                .body(MEMBERS + ".size()", equalTo(5))
                .body(MEMBERS + ".UserName", everyItem(equalTo(LIMIT_USER)));
        call("DeleteAccessKey", "UserName", LIMIT_USER, "AccessKeyId", accessKeyId).statusCode(200);
    }

    @Test
    @Order(15)
    void unknownKeyIdIsNoSuchEntityOnUpdateAndDelete() {
        String unknown = "APKA0000000000000000";
        call("UpdateSSHPublicKey", "UserName", LIMIT_USER, "SSHPublicKeyId", unknown, "Status", "Inactive")
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
        call("DeleteSSHPublicKey", "UserName", LIMIT_USER, "SSHPublicKeyId", unknown)
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
        call("ListSSHPublicKeys", "UserName", LIMIT_USER)
                .statusCode(200)
                .body(MEMBERS + ".size()", equalTo(5))
                .body(MEMBERS + ".Status", everyItem(equalTo("Active")));
    }

    @Test
    @Order(16)
    void nonRsaBodiesAreInvalidPublicKey() throws Exception {
        String rsaBlob = sshBody.substring("ssh-rsa ".length());
        // RSA material under another algorithm's name.
        String wrongHeader = "ssh-dss " + rsaBlob;
        // ssh-rsa header over a blob whose embedded type is not ssh-rsa.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        for (byte[] field : new byte[][]{"ssh-dss".getBytes(), rsaKey.getPublicExponent().toByteArray(),
                rsaKey.getModulus().toByteArray()}) {
            out.writeInt(field.length);
            out.write(field);
        }
        String wrongBlobType = "ssh-rsa " + Base64.getEncoder().encodeToString(bytes.toByteArray());
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        String ecPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(ec.generateKeyPair().getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        // The user is at its 5-key limit, so use a fresh one: rejection must come from the body.
        call("CreateUser", "UserName", USER).statusCode(200);
        for (String body : List.of(wrongHeader, wrongBlobType, ecPem)) {
            call("UploadSSHPublicKey", "UserName", USER, "SSHPublicKeyBody", body)
                    .statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("InvalidPublicKey"));
        }
        String list = call("ListSSHPublicKeys", "UserName", USER).statusCode(200).extract().asString();
        assertEquals("", new XmlPath(list).getString(
                "ListSSHPublicKeysResponse.ListSSHPublicKeysResult.SSHPublicKeys"));
        call("DeleteUser", "UserName", USER).statusCode(200);
    }
}

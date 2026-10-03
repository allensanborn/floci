package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Key material for IAM SSH public keys (UploadSSHPublicKey and friends).
 *
 * <p>IAM accepts "ssh-rsa format or PEM format" with a "minimum bit-length of ... 2048 bits", and
 * reports the key's Fingerprint as "the MD5 message digest of the SSH public key", which AWS shows
 * as colon-separated hex. That is OpenSSH's MD5 fingerprint ({@code ssh-keygen -l -E md5}): the
 * MD5 of the decoded ssh-rsa wire blob.
 *
 * @see <a href="https://docs.aws.amazon.com/IAM/latest/APIReference/API_UploadSSHPublicKey.html">UploadSSHPublicKey</a>
 * @see <a href="https://docs.aws.amazon.com/IAM/latest/APIReference/API_SSHPublicKey.html">SSHPublicKey</a>
 */
final class IamSshPublicKeys {

    private static final int MIN_RSA_BITS = 2048;
    private static final String SSH_RSA = "ssh-rsa";
    private static final String PEM_BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String PEM_END = "-----END PUBLIC KEY-----";

    private IamSshPublicKeys() {}

    static boolean isPem(String body) {
        return body.trim().startsWith(PEM_BEGIN);
    }

    /** Parses an ssh-rsa or PEM body, rejecting anything else with {@code InvalidPublicKey}. */
    static RSAPublicKey parse(String body) {
        RSAPublicKey key;
        try {
            String trimmed = body.trim();
            if (isPem(trimmed)) {
                byte[] der = Base64.getMimeDecoder().decode(trimmed.replace(PEM_BEGIN, "").replace(PEM_END, ""));
                key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            } else {
                String[] fields = trimmed.split("\\s+");
                if (fields.length < 2 || !SSH_RSA.equals(fields[0])) {
                    throw invalid();
                }
                ByteBuffer blob = ByteBuffer.wrap(Base64.getDecoder().decode(fields[1]));
                if (!SSH_RSA.equals(new String(readField(blob), StandardCharsets.US_ASCII))) {
                    throw invalid();
                }
                BigInteger exponent = new BigInteger(readField(blob));
                BigInteger modulus = new BigInteger(readField(blob));
                if (blob.hasRemaining()) {
                    throw invalid();
                }
                key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent));
            }
        } catch (AwsException e) {
            throw e;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw invalid();
        }
        if (key.getModulus().bitLength() < MIN_RSA_BITS) {
            throw invalid();
        }
        return key;
    }

    static String sshBody(RSAPublicKey key) {
        return SSH_RSA + " " + Base64.getEncoder().encodeToString(blob(key));
    }

    static String pemBody(RSAPublicKey key) {
        return PEM_BEGIN + "\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n" + PEM_END + "\n";
    }

    static String fingerprint(RSAPublicKey key) {
        try {
            return HexFormat.of().withDelimiter(":").formatHex(MessageDigest.getInstance("MD5").digest(blob(key)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static byte[] blob(RSAPublicKey key) {
        byte[] type = SSH_RSA.getBytes(StandardCharsets.US_ASCII);
        byte[] exponent = key.getPublicExponent().toByteArray();
        byte[] modulus = key.getModulus().toByteArray();
        ByteBuffer blob = ByteBuffer.allocate(12 + type.length + exponent.length + modulus.length);
        for (byte[] field : new byte[][]{type, exponent, modulus}) {
            blob.putInt(field.length).put(field);
        }
        return blob.array();
    }

    // The declared length is caller-controlled: bound it by what the buffer holds before allocating.
    private static byte[] readField(ByteBuffer blob) {
        int length = blob.remaining() < Integer.BYTES ? -1 : blob.getInt();
        if (length < 0 || length > blob.remaining()) {
            throw invalid();
        }
        byte[] field = new byte[length];
        blob.get(field);
        return field;
    }

    private static AwsException invalid() {
        return new AwsException("InvalidPublicKey",
                "The public key is malformed or otherwise invalid.", 400);
    }
}

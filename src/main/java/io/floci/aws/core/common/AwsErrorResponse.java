package io.floci.aws.core.common;

import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public record AwsErrorResponse(String type, String message, boolean omitNullMessage) {

    public AwsErrorResponse(String type, String message) {
        this(type, message, false);
    }

    /**
     * The error an awsJson or smithy-rpc-v2-cbor service writes. A null message is left off
     * rather than written as {@code null}: botocore reads an absent member as the empty string and
     * a null one as {@code None}, and these protocols send no message for an error thrown with none.
     * rest-json keeps {@code "message":null}, which AWS does send there, so it uses the 2-arg constructor.
     */
    public static AwsErrorResponse omittingNullMessage(String type, String message) {
        return new AwsErrorResponse(type, message, true);
    }

    @JsonValue
    Map<String, String> wire() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("__type", type);
        if (message != null || !omitNullMessage) {
            body.put("message", message);
        }
        return body;
    }
}

package io.floci.aws.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class FieldLevelEncryptionConfig {

    private String id;
    private Instant lastModifiedTime;
    private String etag;
    private String callerReference;
    private String comment;
    /** The whole {@code FieldLevelEncryptionConfig} element as the client sent it, replayed on every read. */
    private String configXml;

    public FieldLevelEncryptionConfig() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getConfigXml() { return configXml; }
    public void setConfigXml(String configXml) { this.configXml = configXml; }
}

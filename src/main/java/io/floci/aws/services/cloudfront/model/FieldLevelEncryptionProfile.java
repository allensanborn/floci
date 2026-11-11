package io.floci.aws.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class FieldLevelEncryptionProfile {

    private String id;
    private Instant lastModifiedTime;
    private String etag;
    private String name;
    private String callerReference;
    private String comment;
    /** The whole {@code FieldLevelEncryptionProfileConfig} element as the client sent it, replayed on every read. */
    private String configXml;

    public FieldLevelEncryptionProfile() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCallerReference() { return callerReference; }
    public void setCallerReference(String callerReference) { this.callerReference = callerReference; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public String getConfigXml() { return configXml; }
    public void setConfigXml(String configXml) { this.configXml = configXml; }
}

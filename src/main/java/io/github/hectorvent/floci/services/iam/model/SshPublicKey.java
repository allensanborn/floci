package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SshPublicKey {

    private String sshPublicKeyId;
    private String fingerprint;
    private String sshPublicKeyBody; // ssh-rsa form; PEM is derived on read
    private String status; // Active | Inactive
    private Instant uploadDate;

    public SshPublicKey() {}

    public SshPublicKey(String sshPublicKeyId, String fingerprint, String sshPublicKeyBody) {
        this.sshPublicKeyId = sshPublicKeyId;
        this.fingerprint = fingerprint;
        this.sshPublicKeyBody = sshPublicKeyBody;
        this.status = "Active";
        this.uploadDate = Instant.now();
    }

    public String getSshPublicKeyId() { return sshPublicKeyId; }
    public void setSshPublicKeyId(String sshPublicKeyId) { this.sshPublicKeyId = sshPublicKeyId; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getSshPublicKeyBody() { return sshPublicKeyBody; }
    public void setSshPublicKeyBody(String sshPublicKeyBody) { this.sshPublicKeyBody = sshPublicKeyBody; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getUploadDate() { return uploadDate; }
    public void setUploadDate(Instant uploadDate) { this.uploadDate = uploadDate; }
}

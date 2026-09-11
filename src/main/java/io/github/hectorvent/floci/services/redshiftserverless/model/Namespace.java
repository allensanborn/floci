package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Namespace {
    private String namespaceName;
    private String namespaceId;
    private String namespaceArn;
    private String adminUsername;
    private String dbName;
    private String kmsKeyId;
    private String defaultIamRoleArn;
    private List<String> iamRoles = new ArrayList<>();
    private List<String> logExports = new ArrayList<>();
    private String status;
    private Instant creationDate;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Namespace() {
    }

    public String getNamespaceName() {
        return namespaceName;
    }

    public void setNamespaceName(String namespaceName) {
        this.namespaceName = namespaceName;
    }

    public String getNamespaceId() {
        return namespaceId;
    }

    public void setNamespaceId(String namespaceId) {
        this.namespaceId = namespaceId;
    }

    public String getNamespaceArn() {
        return namespaceArn;
    }

    public void setNamespaceArn(String namespaceArn) {
        this.namespaceArn = namespaceArn;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String getDefaultIamRoleArn() {
        return defaultIamRoleArn;
    }

    public void setDefaultIamRoleArn(String defaultIamRoleArn) {
        this.defaultIamRoleArn = defaultIamRoleArn;
    }

    public List<String> getIamRoles() {
        return iamRoles;
    }

    public void setIamRoles(List<String> iamRoles) {
        this.iamRoles = iamRoles;
    }

    public List<String> getLogExports() {
        return logExports;
    }

    public void setLogExports(List<String> logExports) {
        this.logExports = logExports;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(Instant creationDate) {
        this.creationDate = creationDate;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}

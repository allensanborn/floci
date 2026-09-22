package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.*;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class);

    private static final List<String> SUPPORTED_RESOURCE_TYPES = List.of(
            "S3", "RDS", "DynamoDB", "EFS", "EC2", "EBS",
            "Aurora", "DocumentDB", "Neptune", "FSx", "VirtualMachine"
    );

    private final StorageBackend<String, BackupVault>     vaultStore;
    private final StorageBackend<String, BackupPlan>      planStore;
    private final StorageBackend<String, BackupSelection> selectionStore;
    private final StorageBackend<String, BackupJob>       jobStore;
    private final StorageBackend<String, RecoveryPoint>   recoveryStore;
    private final StorageBackend<String, String>          accessPolicyStore;
    private final StorageBackend<String, BackupVaultNotifications> notificationStore;

    private final RegionResolver regionResolver;
    private final int jobCompletionDelaySeconds;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backup-job-scheduler");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public BackupService(StorageFactory storageFactory, EmulatorConfig config, RegionResolver regionResolver) {
        this.vaultStore     = storageFactory.create("backup", "backup-vaults.json",     new TypeReference<>() {});
        this.planStore      = storageFactory.create("backup", "backup-plans.json",      new TypeReference<>() {});
        this.selectionStore = storageFactory.create("backup", "backup-selections.json", new TypeReference<>() {});
        this.jobStore       = storageFactory.create("backup", "backup-jobs.json",       new TypeReference<>() {});
        this.recoveryStore  = storageFactory.create("backup", "backup-recovery-points.json", new TypeReference<>() {});
        this.accessPolicyStore  = storageFactory.create("backup", "backup-vault-access-policies.json", new TypeReference<>() {});
        this.notificationStore  = storageFactory.create("backup", "backup-vault-notifications.json",   new TypeReference<>() {});
        this.regionResolver = regionResolver;
        this.jobCompletionDelaySeconds = config.services().backup().jobCompletionDelaySeconds();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ── Vault ──────────────────────────────────────────────────────────────────

    public BackupVault createBackupVault(String vaultName, String encryptionKeyArn,
                                         String creatorRequestId, Map<String, String> tags,
                                         String region) {
        String key = vaultKey(region, vaultName);
        if (vaultStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Backup vault already exists: " + vaultName, 400);
        }
        BackupVault vault = new BackupVault();
        vault.setBackupVaultName(vaultName);
        vault.setBackupVaultArn(regionResolver.buildArn("backup", region, "backup-vault:" + vaultName));
        vault.setEncryptionKeyArn(encryptionKeyArn);
        vault.setCreationDate(Instant.now().getEpochSecond());
        vault.setCreatorRequestId(creatorRequestId);
        vault.setNumberOfRecoveryPoints(0);
        vault.setTags(tags);
        vaultStore.put(key, vault);
        LOG.infov("Created backup vault {0} in {1}", vaultName, region);
        return vault;
    }

    public BackupVault describeBackupVault(String vaultName, String region) {
        return vaultStore.get(vaultKey(region, vaultName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup vault not found: " + vaultName, 404));
    }

    public void deleteBackupVault(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (vault.getNumberOfRecoveryPoints() > 0) {
            throw new AwsException("InvalidRequestException",
                    "Non-empty backup vault cannot be deleted: " + vaultName, 400);
        }
        // No lock check here, deliberately. A lock protects the RECOVERY POINTS, not the
        // vault shell: AWS's guide says the vault "can be deleted if it is empty and does
        // not contain any recovery points", even under a compliance lock. The non-empty
        // check above is already the case AWS refuses, so an added lock check would only
        // ever fire where AWS succeeds — and it would break CloudFormation stack teardown,
        // which deletes vaults through BackupVaultCfnProvisioner and tolerates only
        // not-found.
        String key = vaultKey(region, vaultName);
        vaultStore.delete(key);
        // Drop the sub-resources with the vault. They are keyed by vault name, so
        // leaving them behind would silently graft an old policy or notification
        // configuration onto the next vault created with the same name.
        accessPolicyStore.delete(key);
        notificationStore.delete(key);
    }

    public List<BackupVault> listBackupVaults(String region) {
        String prefix = region + ":";
        return vaultStore.scan(k -> k.startsWith(prefix));
    }

    // ── Vault sub-resources: access policy, notifications, lock ────────────────
    //
    // Each of these first resolves the vault through describeBackupVault, so a call
    // naming a vault that does not exist reports that, rather than reporting the
    // sub-resource as merely unconfigured. The two are different answers to different
    // questions and AWS distinguishes them; collapsing them would tell a caller its
    // typo'd vault name was fine.

    /**
     * Every BackupVaultEvent value the PutBackupVaultNotifications reference lists,
     * including the ones AWS marks deprecated — they are still accepted values, and
     * rejecting one would fail an apply AWS allows.
     *
     * <p>All 30 of them. An earlier revision carried 17, which is the failure mode this
     * whole validation has to avoid: a list that rejects is only as good as it is
     * complete, and a stale one turns a valid configuration into a 400.
     */
    private static final Set<String> BACKUP_VAULT_EVENTS = Set.of(
            "BACKUP_JOB_STARTED", "BACKUP_JOB_COMPLETED", "BACKUP_JOB_SUCCESSFUL",
            "BACKUP_JOB_FAILED", "BACKUP_JOB_EXPIRED",
            "RESTORE_JOB_STARTED", "RESTORE_JOB_COMPLETED", "RESTORE_JOB_SUCCESSFUL",
            "RESTORE_JOB_FAILED",
            "COPY_JOB_STARTED", "COPY_JOB_SUCCESSFUL", "COPY_JOB_FAILED",
            "RECOVERY_POINT_MODIFIED",
            "BACKUP_PLAN_CREATED", "BACKUP_PLAN_MODIFIED",
            "S3_BACKUP_OBJECT_FAILED", "S3_RESTORE_OBJECT_FAILED",
            "CONTINUOUS_BACKUP_INTERRUPTED",
            "RECOVERY_POINT_INDEX_COMPLETED", "RECOVERY_POINT_INDEX_DELETED",
            "RECOVERY_POINT_INDEXING_FAILED",
            "EKS_RESTORE_OBJECT_FAILED", "EKS_RESTORE_OBJECT_SKIPPED",
            "EKS_BACKUP_OBJECT_FAILED",
            "ACCESS_POINT_AVAILABLE", "ACCESS_POINT_CREATION_FAILED",
            "ACCESS_POINT_DELETED", "ACCESS_POINT_DELETION_FAILED",
            "ACCESS_POINT_EXPIRED", "ACCESS_POINT_DISASSOCIATED");

    public void putBackupVaultAccessPolicy(String vaultName, String region, String policy) {
        describeBackupVault(vaultName, region);
        if (policy == null || policy.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "Policy must be a non-empty resource policy document", 400);
        }
        accessPolicyStore.put(vaultKey(region, vaultName), policy);
    }

    public String getBackupVaultAccessPolicy(String vaultName, String region) {
        describeBackupVault(vaultName, region);
        return accessPolicyStore.get(vaultKey(region, vaultName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No access policy found for backup vault: " + vaultName, 400));
    }

    /**
     * Deleting an access policy that is not set is a no-op, matching AWS: the
     * operation is idempotent, so Terraform destroying a configuration twice does not
     * fail the second time.
     */
    public void deleteBackupVaultAccessPolicy(String vaultName, String region) {
        describeBackupVault(vaultName, region);
        accessPolicyStore.delete(vaultKey(region, vaultName));
    }

    public void putBackupVaultNotifications(String vaultName, String region,
                                            String snsTopicArn, List<String> events) {
        describeBackupVault(vaultName, region);
        if (snsTopicArn == null || snsTopicArn.isBlank()) {
            throw new AwsException("InvalidParameterValueException",
                    "SNSTopicArn is required", 400);
        }
        if (events == null || events.isEmpty()) {
            throw new AwsException("InvalidParameterValueException",
                    "BackupVaultEvents must name at least one event", 400);
        }
        // Reject unknown events rather than storing them. An emulator that accepts a
        // misspelt event and reports it back unchanged lets a configuration that real
        // AWS refuses pass a local test, which is the failure mode this corpus exists
        // to catch.
        List<String> unknown = events.stream().filter(e -> !BACKUP_VAULT_EVENTS.contains(e)).toList();
        if (!unknown.isEmpty()) {
            throw new AwsException("InvalidParameterValueException",
                    "Invalid backup vault event(s): " + String.join(", ", unknown), 400);
        }
        notificationStore.put(vaultKey(region, vaultName),
                new BackupVaultNotifications(snsTopicArn, events));
    }

    public BackupVaultNotifications getBackupVaultNotifications(String vaultName, String region) {
        describeBackupVault(vaultName, region);
        return notificationStore.get(vaultKey(region, vaultName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No notification configuration found for backup vault: " + vaultName, 400));
    }

    /** Idempotent, for the same reason as {@link #deleteBackupVaultAccessPolicy}. */
    public void deleteBackupVaultNotifications(String vaultName, String region) {
        describeBackupVault(vaultName, region);
        notificationStore.delete(vaultKey(region, vaultName));
    }

    /**
     * Apply a Vault Lock.
     *
     * <p>AWS has two modes and {@code ChangeableForDays} is what selects them, in the
     * direction that reads backwards at first glance:
     *
     * <ul>
     *   <li><b>Absent → governance mode.</b> "If this parameter is not specified, you
     *       can delete Vault Lock from the vault using DeleteBackupVaultLockConfiguration
     *       or change the Vault Lock configuration using PutBackupVaultLockConfiguration
     *       at any time." No lock date is set and the lock never becomes immutable.</li>
     *   <li><b>Present → compliance mode.</b> The lock date is that many days out, and
     *       "before the lock date, you can delete Vault Lock ... On and after the lock
     *       date, the Vault Lock becomes immutable and cannot be changed or deleted."
     *       AWS enforces a 72-hour cooling-off period, hence the floor of 3.</li>
     * </ul>
     *
     * <p>An earlier revision of this method had the two the wrong way round, which made
     * AWS's freely removable governance lock permanent. Both quotations above are from
     * the PutBackupVaultLockConfiguration reference; the Vault Lock guide states the
     * mapping the same way ("If you wish to create a vault lock in governance mode, do
     * not include ChangeableForDays").
     */
    public BackupVault putBackupVaultLockConfiguration(String vaultName, String region,
                                                       Long minRetentionDays,
                                                       Long maxRetentionDays,
                                                       Long changeableForDays) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (vault.isLocked() && !lockIsStillChangeable(vault)) {
            throw new AwsException("InvalidRequestException",
                    "Backup vault lock is immutable and cannot be changed: " + vaultName, 400);
        }
        if (minRetentionDays != null && minRetentionDays < 1) {
            throw new AwsException("InvalidParameterValueException",
                    "MinRetentionDays must be at least 1", 400);
        }
        if (maxRetentionDays != null && maxRetentionDays < 1) {
            throw new AwsException("InvalidParameterValueException",
                    "MaxRetentionDays must be at least 1", 400);
        }
        if (minRetentionDays != null && maxRetentionDays != null && maxRetentionDays < minRetentionDays) {
            throw new AwsException("InvalidParameterValueException",
                    "MaxRetentionDays must be greater than or equal to MinRetentionDays", 400);
        }
        if (changeableForDays != null && changeableForDays < 3) {
            // AWS's documented floor. Below it a governance lock would be effectively
            // immutable on creation, which is what compliance mode is for.
            throw new AwsException("InvalidParameterValueException",
                    "ChangeableForDays must be at least 3", 400);
        }
        vault.setLocked(true);
        vault.setMinRetentionDays(minRetentionDays);
        vault.setMaxRetentionDays(maxRetentionDays);
        vault.setLockDate(changeableForDays == null ? null
                : Instant.now().plus(changeableForDays, java.time.temporal.ChronoUnit.DAYS).getEpochSecond());
        vaultStore.put(vaultKey(region, vaultName), vault);
        LOG.infov("Locked backup vault {0} in {1} (changeable for {2} day(s))",
                vaultName, region, changeableForDays);
        return vault;
    }

    public void deleteBackupVaultLockConfiguration(String vaultName, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);
        if (vault.isLocked() && !lockIsStillChangeable(vault)) {
            throw new AwsException("InvalidRequestException",
                    "Backup vault lock is immutable and cannot be deleted: " + vaultName, 400);
        }
        vault.setLocked(false);
        vault.setLockDate(null);
        vault.setMinRetentionDays(null);
        vault.setMaxRetentionDays(null);
        vaultStore.put(vaultKey(region, vaultName), vault);
    }

    /**
     * True while a lock can still be changed or removed.
     *
     * <p>A governance lock carries no LockDate and is always changeable. A compliance
     * lock is changeable only before its LockDate. Absent means governance, so a null
     * LockDate must answer TRUE here — the inverse of this is the defect that made a
     * governance lock permanent.
     */
    private static boolean lockIsStillChangeable(BackupVault vault) {
        Long lockDate = vault.getLockDate();
        return lockDate == null || Instant.now().getEpochSecond() < lockDate;
    }

    // ── Plan ───────────────────────────────────────────────────────────────────

    public BackupPlan createBackupPlan(String planName, List<BackupRule> rules,
                                       String creatorRequestId, String region) {
        String planId = UUID.randomUUID().toString();
        BackupPlan plan = new BackupPlan();
        plan.setBackupPlanId(planId);
        plan.setBackupPlanArn(regionResolver.buildArn("backup", region, "backup-plan:" + planId));
        plan.setBackupPlanName(planName);
        plan.setCreationDate(Instant.now().getEpochSecond());
        plan.setVersionId(shortId());
        assignRuleIds(rules);
        plan.setRules(rules);
        planStore.put(planId, plan);
        return plan;
    }

    public BackupPlan getBackupPlan(String planId) {
        return planStore.get(planId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup plan not found: " + planId, 404));
    }

    public BackupPlan updateBackupPlan(String planId, String planName, List<BackupRule> rules) {
        BackupPlan plan = getBackupPlan(planId);
        if (planName != null) {
            plan.setBackupPlanName(planName);
        }
        assignRuleIds(rules);
        plan.setRules(rules);
        plan.setVersionId(shortId());
        planStore.put(planId, plan);
        return plan;
    }

    public void deleteBackupPlan(String planId) {
        getBackupPlan(planId);
        long selectionCount = selectionStore.scan(k -> true).stream()
                .filter(s -> planId.equals(s.getBackupPlanId()))
                .count();
        if (selectionCount > 0) {
            throw new AwsException("InvalidRequestException",
                    "Backup plan has active selections and cannot be deleted", 400);
        }
        planStore.delete(planId);
    }

    public List<BackupPlan> listBackupPlans() {
        return planStore.scan(k -> true);
    }

    // ── Selection ──────────────────────────────────────────────────────────────

    public BackupSelection createBackupSelection(String planId, String selectionName,
                                                  String iamRoleArn, List<String> resources,
                                                  List<String> notResources, String creatorRequestId) {
        getBackupPlan(planId);
        String selectionId = UUID.randomUUID().toString();
        BackupSelection selection = new BackupSelection();
        selection.setSelectionId(selectionId);
        selection.setSelectionName(selectionName);
        selection.setBackupPlanId(planId);
        selection.setIamRoleArn(iamRoleArn);
        selection.setResources(resources);
        selection.setNotResources(notResources);
        selection.setCreationDate(Instant.now().getEpochSecond());
        selection.setCreatorRequestId(creatorRequestId);
        selectionStore.put(selectionId, selection);
        return selection;
    }

    public BackupSelection getBackupSelection(String planId, String selectionId) {
        BackupSelection sel = selectionStore.get(selectionId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup selection not found: " + selectionId, 404));
        if (!planId.equals(sel.getBackupPlanId())) {
            throw new AwsException("ResourceNotFoundException", "Backup selection not found in plan: " + planId, 404);
        }
        return sel;
    }

    public void deleteBackupSelection(String planId, String selectionId) {
        getBackupSelection(planId, selectionId);
        selectionStore.delete(selectionId);
    }

    public List<BackupSelection> listBackupSelections(String planId) {
        return selectionStore.scan(k -> true).stream()
                .filter(s -> planId.equals(s.getBackupPlanId()))
                .toList();
    }

    // ── Job ────────────────────────────────────────────────────────────────────

    public BackupJob startBackupJob(String vaultName, String resourceArn, String iamRoleArn,
                                     Lifecycle lifecycle, String region) {
        BackupVault vault = describeBackupVault(vaultName, region);

        String jobId = UUID.randomUUID().toString();
        long now = Instant.now().getEpochSecond();

        BackupJob job = new BackupJob();
        job.setBackupJobId(jobId);
        job.setBackupVaultName(vaultName);
        job.setBackupVaultArn(vault.getBackupVaultArn());
        job.setResourceArn(resourceArn);
        job.setResourceType(inferResourceType(resourceArn));
        job.setIamRoleArn(iamRoleArn);
        job.setState("CREATED");
        job.setPercentDone("0.0");
        job.setCreationDate(now);
        job.setExpectedCompletionDate(now + jobCompletionDelaySeconds);
        job.setStartBy(now + 3600L);
        job.setAccountId(regionResolver.getAccountId());
        jobStore.put(jobId, job);

        scheduler.schedule(() -> transitionJob(jobId, vaultName, region), 1, TimeUnit.SECONDS);
        scheduler.schedule(() -> completeJob(jobId, vaultName, region), jobCompletionDelaySeconds, TimeUnit.SECONDS);

        return job;
    }

    public BackupJob describeBackupJob(String jobId) {
        return jobStore.get(jobId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Backup job not found: " + jobId, 404));
    }

    public void stopBackupJob(String jobId) {
        BackupJob job = describeBackupJob(jobId);
        String state = job.getState();
        if ("COMPLETED".equals(state) || "ABORTED".equals(state) || "FAILED".equals(state)) {
            throw new AwsException("InvalidRequestException",
                    "Job cannot be stopped in state: " + state, 400);
        }
        job.setState("ABORTING");
        job.setStatusMessage("Job stop requested");
        jobStore.put(jobId, job);
        scheduler.schedule(() -> abortJob(jobId), 1, TimeUnit.SECONDS);
    }

    public List<BackupJob> listBackupJobs(String byVaultName, String byState,
                                           String byResourceArn, String byResourceType) {
        return jobStore.scan(k -> true).stream()
                .filter(j -> byVaultName == null || byVaultName.equals(j.getBackupVaultName()))
                .filter(j -> byState == null || byState.equals(j.getState()))
                .filter(j -> byResourceArn == null || byResourceArn.equals(j.getResourceArn()))
                .filter(j -> byResourceType == null || byResourceType.equals(j.getResourceType()))
                .toList();
    }

    // ── Recovery Point ─────────────────────────────────────────────────────────

    public RecoveryPoint describeRecoveryPoint(String vaultName, String recoveryPointArn, String region) {
        describeBackupVault(vaultName, region);
        return recoveryStore.get(recoveryPointArn)
                .filter(rp -> vaultName.equals(rp.getBackupVaultName()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Recovery point not found: " + recoveryPointArn, 404));
    }

    public List<RecoveryPoint> listRecoveryPointsByBackupVault(String vaultName, String region) {
        describeBackupVault(vaultName, region);
        return recoveryStore.scan(k -> true).stream()
                .filter(rp -> vaultName.equals(rp.getBackupVaultName()))
                .toList();
    }

    public void deleteRecoveryPoint(String vaultName, String recoveryPointArn, String region) {
        RecoveryPoint rp = describeRecoveryPoint(vaultName, recoveryPointArn, region);
        recoveryStore.delete(recoveryPointArn);
        decrementVaultCount(vaultName, region);
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    public Map<String, String> listTags(String resourceArn) {
        return findTagsByArn(resourceArn);
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        applyTags(resourceArn, tags);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        removeTags(resourceArn, tagKeys);
    }

    // ── Supported resource types ───────────────────────────────────────────────

    public List<String> getSupportedResourceTypes() {
        return SUPPORTED_RESOURCE_TYPES;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void transitionJob(String jobId, String vaultName, String region) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("CREATED".equals(job.getState())) {
                job.setState("RUNNING");
                job.setPercentDone("50.0");
                jobStore.put(jobId, job);
            }
        });
    }

    private void completeJob(String jobId, String vaultName, String region) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("RUNNING".equals(job.getState())) {
                long now = Instant.now().getEpochSecond();
                String rpArn = regionResolver.buildArn("backup", region,
                        "recovery-point:" + UUID.randomUUID());

                job.setState("COMPLETED");
                job.setPercentDone("100.0");
                job.setCompletionDate(now);
                job.setRecoveryPointArn(rpArn);
                job.setBackupSizeInBytes(0L);
                job.setBytesTransferred(0L);
                jobStore.put(jobId, job);

                RecoveryPoint rp = new RecoveryPoint();
                rp.setRecoveryPointArn(rpArn);
                rp.setBackupVaultName(vaultName);
                rp.setBackupVaultArn(job.getBackupVaultArn());
                rp.setResourceArn(job.getResourceArn());
                rp.setResourceType(job.getResourceType());
                rp.setIamRoleArn(job.getIamRoleArn());
                rp.setStatus("COMPLETED");
                rp.setCreationDate(job.getCreationDate());
                rp.setCompletionDate(now);
                rp.setBackupSizeInBytes(0L);
                rp.setStorageClass("WARM");
                rp.setEncrypted(false);
                recoveryStore.put(rpArn, rp);

                incrementVaultCount(vaultName, region);
                LOG.infov("Backup job {0} completed, recovery point: {1}", jobId, rpArn);
            }
        });
    }

    private void abortJob(String jobId) {
        jobStore.get(jobId).ifPresent(job -> {
            if ("ABORTING".equals(job.getState())) {
                job.setState("ABORTED");
                job.setCompletionDate(Instant.now().getEpochSecond());
                jobStore.put(jobId, job);
            }
        });
    }

    private void incrementVaultCount(String vaultName, String region) {
        vaultStore.get(vaultKey(region, vaultName)).ifPresent(vault -> {
            vault.setNumberOfRecoveryPoints(vault.getNumberOfRecoveryPoints() + 1);
            vaultStore.put(vaultKey(region, vaultName), vault);
        });
    }

    private void decrementVaultCount(String vaultName, String region) {
        vaultStore.get(vaultKey(region, vaultName)).ifPresent(vault -> {
            vault.setNumberOfRecoveryPoints(Math.max(0, vault.getNumberOfRecoveryPoints() - 1));
            vaultStore.put(vaultKey(region, vaultName), vault);
        });
    }

    private Map<String, String> findTagsByArn(String arn) {
        Optional<BackupVault> vault = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vault.isPresent()) {
            return vault.get().getTags();
        }
        Optional<BackupPlan> plan = planStore.scan(k -> true).stream()
                .filter(p -> arn.equals(p.getBackupPlanArn()))
                .findFirst();
        if (plan.isPresent()) {
            return new HashMap<>();
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private void applyTags(String arn, Map<String, String> newTags) {
        Optional<BackupVault> vaultOpt = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vaultOpt.isPresent()) {
            BackupVault vault = vaultOpt.get();
            vault.getTags().putAll(newTags);
            vaultStore.put(vaultKey(vault), vault);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private void removeTags(String arn, List<String> tagKeys) {
        Optional<BackupVault> vaultOpt = vaultStore.scan(k -> true).stream()
                .filter(v -> arn.equals(v.getBackupVaultArn()))
                .findFirst();
        if (vaultOpt.isPresent()) {
            BackupVault vault = vaultOpt.get();
            tagKeys.forEach(vault.getTags()::remove);
            vaultStore.put(vaultKey(vault), vault);
            return;
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private static void assignRuleIds(List<BackupRule> rules) {
        if (rules == null) {
            return;
        }
        for (BackupRule rule : rules) {
            if (rule.getRuleId() == null) {
                rule.setRuleId(UUID.randomUUID().toString());
            }
        }
    }

    private static String inferResourceType(String resourceArn) {
        if (resourceArn == null) {
            return null;
        }
        if (resourceArn.contains(":s3:::")) {
            return "S3";
        }
        if (resourceArn.contains(":rds:")) {
            return "RDS";
        }
        if (resourceArn.contains(":dynamodb:")) {
            return "DynamoDB";
        }
        if (resourceArn.contains(":ec2:")) {
            return "EC2";
        }
        if (resourceArn.contains(":elasticfilesystem:")) {
            return "EFS";
        }
        return null;
    }

    private static String vaultKey(String region, String vaultName) {
        return region + ":" + vaultName;
    }

    private static String vaultKey(BackupVault vault) {
        String region = AwsArnUtils.parse(vault.getBackupVaultArn()).region();
        return region + ":" + vault.getBackupVaultName();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

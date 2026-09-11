# Redshift Serverless

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: RedshiftServerless.<Operation>` and `Content-Type: application/x-amz-json-1.1`

Floci emulates the namespace lifecycle of Amazon Redshift Serverless: the account and Region scoped container that holds a serverless database, its admin credentials, and its IAM roles. This is the surface Terraform's `aws_redshiftserverless_namespace` drives.

For the upstream API shape, see the [Amazon Redshift Serverless API Reference](https://docs.aws.amazon.com/redshift-serverless/latest/APIReference/Welcome.html).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateNamespace` | Create a namespace, applying AWS defaults for `dbName`, `kmsKeyId`, and `logExports` |
| `GetNamespace` | Return a namespace by name |
| `ListNamespaces` | Page through the namespaces in the account and Region |
| `UpdateNamespace` | Apply the supplied fields to an existing namespace and return it |
| `DeleteNamespace` | Remove a namespace and return it with `status` `DELETING` |
<!-- floci:actions:end -->

Namespace state is account and Region scoped and persisted through `StorageFactory`.

## Compatibility Notes

- **Namespaces only.** Workgroups, snapshots, recovery points, usage limits, endpoint access, and the tagging operations are not emulated. A namespace has no compute attached and no endpoint to connect to, so the Redshift Data API still rejects `WorkgroupName`.
- **`adminUserPassword` is accepted and never returned**, matching AWS. No secret is created for `manageAdminPassword`.
- **Defaults follow AWS.** `dbName` defaults to `dev`, `kmsKeyId` to `AWS_OWNED_KMS_KEY`, `logExports` to an empty list, and `status` to `AVAILABLE` immediately: there is no `MODIFYING` or `CREATING` phase to poll through.
- **`namespaceId` is a generated UUID** and `namespaceArn` is `arn:aws:redshift-serverless:<region>:<account>:namespace/<namespaceId>`.
- **`DeleteNamespace` returns the deleted namespace with `status` `DELETING`** and removes it in the same call, so the next `GetNamespace` returns `ResourceNotFoundException`.
- **`UpdateNamespace` applies only the fields present in the request.** An omitted field keeps its stored value; an explicitly empty `iamRoles` or `logExports` array clears it.

## AWS-compatible failures

Namespace names, database names, and log export values are validated. Floci returns `ValidationException` for a malformed request, `ConflictException` when the namespace name is already taken, and `ResourceNotFoundException` for an unknown namespace.

AWS also models `AccessDeniedException`, `InternalServerException`, and `ThrottlingException`. Floci does not inject provider-side failures that cannot be derived from the request or emulator state.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_REDSHIFT_SERVERLESS_ENABLED` | `true` | Enable or disable Redshift Serverless |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws redshift-serverless create-namespace \
  --namespace-name analytics \
  --admin-username admin \
  --admin-user-password Secret123! \
  --db-name dev

aws redshift-serverless get-namespace --namespace-name analytics
aws redshift-serverless list-namespaces
aws redshift-serverless update-namespace --namespace-name analytics --log-exports userlog
aws redshift-serverless delete-namespace --namespace-name analytics
```

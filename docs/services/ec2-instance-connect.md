# EC2 Instance Connect

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSEC2InstanceConnectService.*`)
**Endpoint:** `http://localhost:4566/`

`SendSSHPublicKey` pushes a public key that an instance's
[EC2 Instance Connect agent](https://github.com/aws/aws-ec2-instance-connect-config) accepts for 60
seconds, as on AWS. The agent runs unmodified: Floci serves the signed keys and their signing
material through IMDS, and the agent verifies them exactly as it does on EC2.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `SendSSHPublicKey` | Push a public key for an OS user; the instance's agent accepts it for 60 seconds |
| `SendSerialConsoleSSHPublicKey` | Rejected with `InvalidArgsException`: Docker-backed instances have no serial console |
<!-- floci:actions:end -->

## Validation

Requests are checked against the service model and the instance, with AWS's error codes:

- `InvalidArgsException`: an instance ID, OS user or availability zone outside the model's
  pattern, a key shorter than 80 or longer than 4096 characters, a key type other than RSA,
  ED25519 or ECDSA, a key whose encoded type differs from its prefix, or an RSA key under 2048
  bits.
- `EC2InstanceNotFoundException`: no such instance in the region, or not in the given zone.
- `EC2InstanceStateInvalidException`: the instance is not `running`.

## What the instance sees

IMDS serves the agent's paths under `/latest/meta-data/`:

| Path | Content |
| --- | --- |
| `services/domain` | `amazonaws.com` |
| `managed-ssh-keys/active-keys/<user>/` | The user's live keys, each as `#Timestamp=`, `#Instance=`, `#Caller=` and `#Request=` lines, the key, and a base64 RSA-PSS (SHA-256, salt 32) signature; answers `HEAD`, and `404` when there is none |
| `managed-ssh-keys/signer-cert/` | PEM chain: the signer (`CN=managed-ssh-signer.<region>.amazonaws.com`), a Floci intermediate, Floci's local CA |
| `managed-ssh-keys/signer-ocsp/` | The upper-case SHA-1 fingerprints of the signer and the intermediate |
| `managed-ssh-keys/signer-ocsp/<fingerprint>` | A base64 DER OCSP response, `good`, signed by that certificate's issuer |

On EC2 the chain ends at a public root the AMI already trusts. Floci's ends at its local CA (the
one `GET /_floci/ca.pem` serves), so at launch Floci adds that CA to the guest's system trust
store as `Floci_Local_CA.crt` and runs `update-ca-certificates`; the agent finds it as
`/etc/ssl/certs/Floci_Local_CA.pem`. Images without `update-ca-certificates` are left alone, and
the agent there refuses Floci's keys.

The agent also checks that it runs on EC2: `/sys/hypervisor/uuid` starting with `ec2`, or the DMI
`board_asset_tag` equal to the instance ID. A Docker container has neither, so the guest image
must provide one (the instance ID is in the guest's `AWS_EC2_INSTANCE_ID` environment variable).

## Not emulated

- IAM authorization of `SendSSHPublicKey` beyond Floci's general IAM enforcement, and the
  `ec2:osuser` condition key.
- The signing material lives in memory: a restart issues a new intermediate and signers under the
  same local CA, so guests keep verifying.

## Instance Connect Endpoint tunnels

`aws ec2-instance-connect open-tunnel` and `aws ec2-instance-connect ssh --connection-type eice`
open a SigV4-presigned WebSocket to `wss://<endpoint DnsName>/openTunnel`, and binary frames carry
the TCP stream to the instance. Floci serves that path on its HTTPS listener, so it needs
`FLOCI_TLS_ENABLED=true`. Endpoint DNS names are `<endpoint id>.localhost.floci.io`, which public DNS
resolves to `127.0.0.1` and Floci's wildcard certificate covers.

Two properties of the AWS CLI shape how a client reaches it. The CLI always dials port 443, so the
client's loopback port 443 must reach Floci's port (publish `127.0.0.1:443:4566`, or forward it inside
a client container). And the tunnel's TLS uses the operating system's trust store, not
`AWS_CA_BUNDLE`, so that store must trust Floci's local CA (`GET /_floci/ca.pem`).

Before the WebSocket upgrade, Floci checks what AWS checks and answers a refusal as an HTTP error
the CLI prints:

| Check | Refusal |
| --- | --- |
| The URL is presigned for `ec2-instance-connect` and not past `X-Amz-Expires` | 403 |
| `remotePort` is 22 or 3389, `maxTunnelDuration` is 1 to 3600 seconds | 400 |
| The endpoint exists in a usable state | 404 |
| A running instance holds `privateIpAddress` in the endpoint's VPC | 404 |
| The endpoint's security groups allow the port out to the instance, and the instance's allow it in from the endpoint's address or groups | 403 |
| The instance accepts the TCP connection | 504 |

The tunnel closes after `maxTunnelDuration` (3600 seconds by default). The SigV4 signature itself is
not verified, and with `PreserveClientIp` the instance still sees the endpoint's address.

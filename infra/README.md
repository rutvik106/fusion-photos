# Fusion Photos — AWS infrastructure (Terraform)

Provisions the durable, production-shaped AWS side of the plan. The application
itself is in `../api` (Go) and `../web` (Vue); this directory only creates the
cloud resources they depend on.

## What this creates

| Resource | Purpose | Plan section |
|---|---|---|
| Serving S3 bucket | Versioned, SSE, Block Public Access, ACLs disabled, TLS-only, 7-day incomplete-multipart abort | S3 security |
| Replica S3 bucket (second region) | Independent disaster copy, versioned, optional Object Lock | S3 security & DR |
| Cross-region replication | Immutable objects copied to the replica; **delete markers not mirrored** | Replication scope |
| Aurora PostgreSQL (Multi-AZ) | PITR (`backup_retention_period`), Serverless v2, RDS-managed master password | Data model |
| Aurora cross-region read replica | Recoverable database in the second region | DR targets |
| IAM replication role + app policy | Least privilege: the API can write the serving bucket and only **read** the replica | Owner-only / DR |
| Secrets Manager | `OTP_PEPPER`, SES sender | Auth |
| AWS Budgets + Cost Anomaly Detection | Delayed cost signals | Billing safeguards |

## Relationship to the running service

The API's replication worker (`api/cmd/server/workers.go`) marks an asset
`protected` **only after** every resource is observed in `S3_REPLICA_BUCKET` at the
expected size. Point the deployment at these buckets via the `api_env` output:

```
S3_BUCKET=<serving bucket>
S3_REGION=<primary region>
S3_REPLICA_BUCKET=<replica bucket>
S3_REPLICA_REGION=<replica region>
DATABASE_URL=postgres://... (see the db_master_secret_arn output)
```

With `S3_REPLICA_BUCKET` unset the service makes **no** remote-protection claim.

## Usage

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars   # fill in globally-unique bucket names
terraform init
terraform validate
terraform plan
terraform apply
```

Object Lock can only be enabled **at bucket creation** — decide `enable_object_lock`
before the first apply (flipping it later needs a new bucket).

## Dev / CI

- The Go integration tests (`../scripts/test_integration.sh`) exercise the
  replication-verification and reconciliation paths against **local MinIO**, so no
  AWS account is needed to test the application logic.
- This configuration is validated with `terraform validate` (see the repo CI or run
  the container commands below); it is not applied in CI.

```bash
docker run --rm -v "$PWD:/infra" -w /infra hashicorp/terraform:latest init -backend=false
docker run --rm -v "$PWD:/infra" -w /infra hashicorp/terraform:latest validate
docker run --rm -v "$PWD:/infra" -w /infra hashicorp/terraform:latest fmt -check -recursive
```

## Deliberate simplifications

- Uses the **default VPC**; production should use a dedicated VPC and tighten the DB
  security group to the API's group.
- Replication is **same-account, cross-region**. A cross-account copy additionally
  needs a destination bucket policy and account-level grants.
- Object Lock defaults to **off**; enable it once recovery drills are in place.
- No CloudFront, lifecycle tiering, or app autoscaling yet — those follow measured usage.

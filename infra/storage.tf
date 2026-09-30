# Serving bucket — the immediately readable copy that clients transfer to/from.
# Implements the plan's S3 security baseline: Block Public Access, ACLs disabled
# (bucket-owner enforced), TLS-only, SSE, versioning, and an incomplete-multipart
# expiry so abandoned parts stop accruing charges.

resource "aws_s3_bucket" "serving" {
  bucket = var.serving_bucket
  tags   = local.tags
}

resource "aws_s3_bucket_versioning" "serving" {
  bucket = aws_s3_bucket.serving.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_ownership_controls" "serving" {
  bucket = aws_s3_bucket.serving.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_public_access_block" "serving" {
  bucket                  = aws_s3_bucket.serving.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "serving" {
  bucket = aws_s3_bucket.serving.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
    bucket_key_enabled = true
  }
}

data "aws_iam_policy_document" "serving_tls_only" {
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.serving.arn, "${aws_s3_bucket.serving.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "serving" {
  bucket     = aws_s3_bucket.serving.id
  policy     = data.aws_iam_policy_document.serving_tls_only.json
  depends_on = [aws_s3_bucket_public_access_block.serving]
}

resource "aws_s3_bucket_lifecycle_configuration" "serving" {
  bucket = aws_s3_bucket.serving.id

  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = var.incomplete_multipart_days
    }
  }

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

# Replica bucket — the independent copy in a second region. Versioning is required
# for replication; Object Lock (if enabled) must be set at creation.

resource "aws_s3_bucket" "replica" {
  provider            = aws.replica
  bucket              = var.replica_bucket
  object_lock_enabled = var.enable_object_lock
  tags                = local.tags
}

resource "aws_s3_bucket_versioning" "replica" {
  provider = aws.replica
  bucket   = aws_s3_bucket.replica.id
  versioning_configuration { status = "Enabled" }
}

resource "aws_s3_bucket_ownership_controls" "replica" {
  provider = aws.replica
  bucket   = aws_s3_bucket.replica.id
  rule { object_ownership = "BucketOwnerEnforced" }
}

resource "aws_s3_bucket_public_access_block" "replica" {
  provider                = aws.replica
  bucket                  = aws_s3_bucket.replica.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "replica" {
  provider = aws.replica
  bucket   = aws_s3_bucket.replica.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "replica" {
  count    = var.enable_object_lock ? 1 : 0
  provider = aws.replica
  bucket   = aws_s3_bucket.replica.id
  rule {
    default_retention {
      mode = "GOVERNANCE"
      days = var.object_lock_days
    }
  }
  depends_on = [aws_s3_bucket_versioning.replica]
}

# Cross-region replication: immutable objects are copied to the replica bucket.
# Delete markers are deliberately NOT mirrored — the plan requires a delayed,
# controlled deletion job so a user delete cannot instantly destroy the disaster copy.
resource "aws_s3_bucket_replication_configuration" "serving" {
  depends_on = [
    aws_s3_bucket_versioning.serving,
    aws_s3_bucket_versioning.replica,
  ]
  role   = aws_iam_role.replication.arn
  bucket = aws_s3_bucket.serving.id

  rule {
    id     = "cross-region-copy"
    status = "Enabled"
    filter {}
    destination {
      bucket        = aws_s3_bucket.replica.arn
      storage_class = "STANDARD"
      # Enable a 15-minute replication target (billable) once measurements justify it:
      # replication_time {
      #   status = "Enabled"
      #   time { minutes = 15 }
      # }
    }
    delete_marker_replication {
      status = "Disabled"
    }
  }
}

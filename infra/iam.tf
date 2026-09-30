# IAM for the service. The API can read/write the serving bucket and READ the replica
# (to verify protection), but it must NOT be able to delete replica objects, change
# retention, or disable replication — that separation is the point of the disaster copy.

resource "aws_iam_role" "replication" {
  name               = "${var.name_prefix}-s3-replication"
  assume_role_policy = data.aws_iam_policy_document.replication_assume.json
  tags               = local.tags
}

data "aws_iam_policy_document" "replication_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = [aws_s3_bucket.serving.arn]
    }
  }
}

data "aws_iam_policy_document" "replication" {
  statement {
    sid       = "ReadSource"
    effect    = "Allow"
    actions   = ["s3:GetReplicationConfiguration", "s3:ListBucket"]
    resources = [aws_s3_bucket.serving.arn]
  }
  statement {
    sid    = "ReadSourceObjects"
    effect = "Allow"
    actions = [
      "s3:GetObjectVersionForReplication",
      "s3:GetObjectVersionAcl",
      "s3:GetObjectVersionTagging",
    ]
    resources = ["${aws_s3_bucket.serving.arn}/*"]
  }
  statement {
    sid    = "WriteReplica"
    effect = "Allow"
    actions = [
      "s3:ReplicateObject",
      "s3:ReplicateDelete",
      "s3:ReplicateTags",
      "s3:ObjectOwnerOverrideToBucketOwner",
    ]
    resources = ["${aws_s3_bucket.replica.arn}/*"]
  }
}

resource "aws_iam_role_policy" "replication" {
  name   = "replication"
  role   = aws_iam_role.replication.id
  policy = data.aws_iam_policy_document.replication.json
}

# ---- application identity ----

data "aws_iam_policy_document" "app" {
  statement {
    sid    = "ServingReadWrite"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
      "s3:ListBucketMultipartUploads",
    ]
    resources = [aws_s3_bucket.serving.arn, "${aws_s3_bucket.serving.arn}/*"]
  }
  statement {
    sid       = "ServingList"
    effect    = "Allow"
    actions   = ["s3:GetBucketLocation", "s3:ListBucket"]
    resources = [aws_s3_bucket.serving.arn]
  }
  statement {
    sid       = "ReplicaVerifyReadOnly"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:ListBucket", "s3:GetBucketLocation"]
    resources = [aws_s3_bucket.replica.arn, "${aws_s3_bucket.replica.arn}/*"]
  }
  statement {
    sid       = "SendSignInCodes"
    effect    = "Allow"
    actions   = ["ses:SendEmail"]
    resources = ["*"] # scope to the verified SES identity ARN in production
  }
  statement {
    sid       = "ReadAppSecret"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.app.arn]
  }
}

resource "aws_iam_user" "app" {
  name = var.app_iam_user_name
  tags = local.tags
}

resource "aws_iam_user_policy" "app" {
  name   = "${var.name_prefix}-api"
  user   = aws_iam_user.app.name
  policy = data.aws_iam_policy_document.app.json
}
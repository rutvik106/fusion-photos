output "serving_bucket" {
  value = aws_s3_bucket.serving.bucket
}

output "replica_bucket" {
  value = aws_s3_bucket.replica.bucket
}

output "db_writer_endpoint" {
  value = aws_rds_cluster.primary.endpoint
}

output "db_reader_endpoint" {
  value = aws_rds_cluster.primary.reader_endpoint
}

output "db_master_secret_arn" {
  description = "Secrets Manager ARN holding the RDS-managed master password."
  value       = aws_rds_cluster.primary.master_user_secret[0].secret_arn
}

output "app_secret_arn" {
  value = aws_secretsmanager_secret.app.arn
}

output "app_iam_user" {
  value = aws_iam_user.app.name
}

output "api_env" {
  description = "Values to feed the API environment (never commit the password)."
  value = {
    S3_BUCKET         = aws_s3_bucket.serving.bucket
    S3_REGION         = var.aws_region
    S3_REPLICA_BUCKET = aws_s3_bucket.replica.bucket
    S3_REPLICA_REGION = var.replica_region
    DATABASE_URL      = "postgres://fusionadmin:<from Secrets Manager>@${aws_rds_cluster.primary.endpoint}:5432/fusion?sslmode=require"
  }
}

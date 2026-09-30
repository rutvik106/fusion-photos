variable "aws_region" {
  description = "Primary (serving) region."
  type        = string
  default     = "us-east-1"
}

variable "replica_region" {
  description = "Independent copy region for the disaster bucket and the DB replica."
  type        = string
  default     = "us-west-2"
}

variable "name_prefix" {
  description = "Prefix for named resources."
  type        = string
  default     = "fusion-photos"
}

variable "serving_bucket" {
  description = "Globally unique name of the serving bucket."
  type        = string
}

variable "replica_bucket" {
  description = "Globally unique name of the independent replica bucket."
  type        = string
}

variable "enable_object_lock" {
  description = <<-EOT
    Enable S3 Object Lock (immutable recovery window) on the replica bucket.
    Object Lock can ONLY be enabled at bucket creation, so turning this on later
    requires creating a new bucket. The app role must never be able to shorten it.
  EOT
  type        = bool
  default     = false
}

variable "object_lock_days" {
  description = "Default retention (days) when Object Lock is enabled. The plan proposes 30."
  type        = number
  default     = 30
}

variable "incomplete_multipart_days" {
  description = "Abort abandoned multipart uploads after this many days (plan proposes 7)."
  type        = number
  default     = 7
}

variable "db_engine_version" {
  description = "Aurora PostgreSQL engine version."
  type        = string
  default     = "16.4"
}

variable "db_instance_count" {
  description = "Writer + reader instance count in the primary region (Multi-AZ availability)."
  type        = number
  default     = 2
}

variable "db_min_acu" {
  description = "Aurora Serverless v2 minimum capacity (ACUs)."
  type        = number
  default     = 0.5
}

variable "db_max_acu" {
  description = "Aurora Serverless v2 maximum capacity (ACUs)."
  type        = number
  default     = 4
}

variable "db_backup_retention_days" {
  description = "Point-in-time-recovery window, in days."
  type        = number
  default     = 30
}

variable "budget_amount_usd" {
  description = "Monthly AWS budget for alerting (application-level safeguards still required)."
  type        = number
  default     = 200
}

variable "budget_alert_emails" {
  description = "Addresses notified when the budget threshold is crossed."
  type        = list(string)
  default     = []
}

variable "app_iam_user_name" {
  description = "IAM user the API runs as when not using a role (dev)."
  type        = string
  default     = "fusion-photos-api"
}

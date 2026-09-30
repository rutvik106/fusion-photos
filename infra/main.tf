terraform {
  required_version = ">= 1.6"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.60" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Recommended for real use — remote state with locking. Fill in and uncomment.
  # backend "s3" {
  #   bucket       = "your-tfstate-bucket"
  #   key          = "fusion-photos/terraform.tfstate"
  #   region       = "us-east-1"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.aws_region
}

provider "aws" {
  alias  = "replica"
  region = var.replica_region
}

data "aws_caller_identity" "current" {}

locals {
  tags = {
    Project   = "fusion-photos"
    ManagedBy = "terraform"
  }
}

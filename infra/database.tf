# Amazon Aurora PostgreSQL with Multi-AZ availability and point-in-time recovery,
# plus an independent cross-region read replica. A pilot may run a smaller footprint,
# but the plan requires PITR and a recoverable second-region deployment.

data "aws_vpc" "primary" {
  default = true # production should use a dedicated VPC
}

data "aws_subnets" "primary" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.primary.id]
  }
}

resource "aws_db_subnet_group" "primary" {
  name       = "${var.name_prefix}-db"
  subnet_ids = data.aws_subnets.primary.ids
  tags       = local.tags
}

resource "aws_security_group" "db" {
  name        = "${var.name_prefix}-db"
  description = "Postgres access for the Fusion Photos API"
  vpc_id      = data.aws_vpc.primary.id

  ingress {
    description = "Postgres (tighten to the API security group in production)"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.primary.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.tags
}

resource "aws_rds_cluster" "primary" {
  cluster_identifier          = "${var.name_prefix}-pg"
  engine                      = "aurora-postgresql"
  engine_mode                 = "provisioned"
  engine_version              = var.db_engine_version
  database_name               = "fusion"
  master_username             = "fusionadmin"
  manage_master_user_password = true
  db_subnet_group_name        = aws_db_subnet_group.primary.name
  vpc_security_group_ids      = [aws_security_group.db.id]
  storage_encrypted           = true
  backup_retention_period     = var.db_backup_retention_days
  preferred_backup_window     = "07:00-09:00"
  copy_tags_to_snapshot       = true
  deletion_protection         = true
  apply_immediately           = false
  serverlessv2_scaling_configuration {
    min_capacity = var.db_min_acu
    max_capacity = var.db_max_acu
  }
  tags = local.tags
}

resource "aws_rds_cluster_instance" "primary" {
  count              = var.db_instance_count
  identifier         = "${var.name_prefix}-pg-${count.index}"
  cluster_identifier = aws_rds_cluster.primary.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.primary.engine
  engine_version     = aws_rds_cluster.primary.engine_version
  tags               = local.tags
}

# ---- cross-region recovery copy ----

data "aws_vpc" "replica" {
  provider = aws.replica
  default  = true
}

data "aws_subnets" "replica" {
  provider = aws.replica
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.replica.id]
  }
}

resource "aws_db_subnet_group" "replica" {
  provider   = aws.replica
  name       = "${var.name_prefix}-db-replica"
  subnet_ids = data.aws_subnets.replica.ids
  tags       = local.tags
}

resource "aws_rds_cluster" "replica" {
  provider                      = aws.replica
  cluster_identifier            = "${var.name_prefix}-pg-replica"
  engine                        = aws_rds_cluster.primary.engine
  engine_mode                   = "provisioned"
  engine_version                = aws_rds_cluster.primary.engine_version
  replication_source_identifier = aws_rds_cluster.primary.arn
  source_region                 = var.aws_region
  db_subnet_group_name          = aws_db_subnet_group.replica.name
  storage_encrypted             = true
  tags                          = local.tags
}

resource "aws_rds_cluster_instance" "replica" {
  provider           = aws.replica
  count              = 1
  identifier         = "${var.name_prefix}-pg-replica-0"
  cluster_identifier = aws_rds_cluster.replica.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.replica.engine
  engine_version     = aws_rds_cluster.replica.engine_version
  tags               = local.tags
}

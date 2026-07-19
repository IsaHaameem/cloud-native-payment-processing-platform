#
# A single RDS PostgreSQL instance — one instance, one database, schema-per-
# service (D4), exactly matching the local docker-compose Postgres container
# every service already migrates against via Flyway. Terraform provisions the
# instance and the `paymentflow` database only; each service's own Flyway
# migration still owns and creates its schema at application boot, unchanged.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

resource "aws_db_subnet_group" "this" {
  name       = "${local.name_prefix}-rds-subnet-group"
  subnet_ids = var.private_subnet_ids

  tags = merge(var.tags, { Name = "${local.name_prefix}-rds-subnet-group" })
}

resource "aws_db_instance" "this" {
  identifier = "${local.name_prefix}-postgres"

  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.allocated_storage_gb * 2 # storage autoscaling ceiling
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.db_name
  username = var.master_username
  password = var.master_password
  port     = 5432

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [var.security_group_id]
  publicly_accessible    = false

  multi_az                  = var.multi_az
  backup_retention_period   = var.backup_retention_days
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${local.name_prefix}-postgres-final"

  auto_minor_version_upgrade = true
  apply_immediately          = true # a dev/demo environment favors immediate application over waiting for the next maintenance window

  tags = merge(var.tags, { Name = "${local.name_prefix}-postgres" })
}

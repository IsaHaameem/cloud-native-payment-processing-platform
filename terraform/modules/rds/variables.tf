variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "engine_version" {
  description = "Matches the local compose stack's Postgres 17 (D-established: same major version dev/prod). 17.10 is the latest 17.x minor RDS actually offers in us-east-1 as of this writing (verified via `aws rds describe-db-engine-versions`) — 17.4 does not exist as an RDS version and made the M11/M12 apply's aws_db_instance create fail outright (see PROJECT_CONTEXT.md decision log)."
  type        = string
  default     = "17.10"
}

variable "instance_class" {
  description = "Small by default — this is a portfolio-scale workload, not a production one (see PROJECT_CONTEXT.md Risks: cost-consciousness)."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage_gb" {
  type    = number
  default = 20
}

variable "db_name" {
  description = "Matches the local schema-per-service database name exactly (D4) — one database, one schema per service, no per-service RDS instances."
  type        = string
  default     = "paymentflow"
}

variable "master_username" {
  type = string
}

variable "master_password" {
  type      = string
  sensitive = true
}

variable "multi_az" {
  description = "Single-AZ by default, matching this project's cost-conscious single-small-RDS stance; set true for an environment that actually needs the failover HA."
  type        = bool
  default     = false
}

variable "backup_retention_days" {
  type    = number
  default = 1
}

variable "deletion_protection" {
  description = "false by default — a portfolio/demo environment needs to be tearable-down on demand (see Risks: teardown scripts)."
  type        = bool
  default     = false
}

variable "skip_final_snapshot" {
  description = "true by default for the same teardown-on-demand reason as deletion_protection."
  type        = bool
  default     = true
}

variable "tags" {
  type    = map(string)
  default = {}
}

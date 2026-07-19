#
# Single-node Redis (AUTH token + in-transit/at-rest encryption), matching the
# local compose Redis's cache-aside/TTL/distributed-lock/rate-limiter usage
# (M4/M5/gateway-service) closely enough that no application code changes —
# only SPRING_DATA_REDIS_HOST/PORT/PASSWORD env vars, exactly like the M9
# container network already does locally.
#
# aws_elasticache_replication_group (not the plain aws_elasticache_cluster
# resource) because an auth_token requires transit_encryption_enabled, which
# only the replication-group resource supports — even at num_cache_clusters=1
# (single node, no read replica, no automatic failover: cost-appropriate for
# this workload, per Risks).
#

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${local.name_prefix}-redis-subnet-group"
  subnet_ids = var.private_subnet_ids

  tags = merge(var.tags, { Name = "${local.name_prefix}-redis-subnet-group" })
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${local.name_prefix}-redis"
  # AWS resource-level description fields (unlike Terraform's own variable/
  # output descriptions) reject several common punctuation marks - no
  # em-dashes, confirmed via the same class of validate failure the
  # security-groups module hit.
  description = "${local.name_prefix} Redis: cache-aside, idempotency locks, gateway rate limiter"

  engine         = "redis"
  engine_version = var.engine_version
  node_type      = var.node_type
  port           = 6379

  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [var.security_group_id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = var.auth_token

  auto_minor_version_upgrade = true
  apply_immediately          = true

  tags = merge(var.tags, { Name = "${local.name_prefix}-redis" })
}

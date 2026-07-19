#
# Least-privilege security groups for every tier: internet-facing ALB, the
# ECS tasks behind it, and each private-tier dependency (RDS, ElastiCache,
# the self-managed Kafka broker). Every non-ALB ingress rule sources from a
# security group ID, never a CIDR — nothing in a private subnet is reachable
# except from the specific SG that legitimately calls it.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

# ── ALB: the platform's one intentional public entry point ─────────────────

resource "aws_security_group" "alb" {
  name        = "${local.name_prefix}-alb-sg"
  description = "Ingress from the internet on 80/443; egress only to the ECS tasks SG."
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${local.name_prefix}-alb-sg" })
}

resource "aws_security_group_rule" "alb_ingress_http" {
  security_group_id = aws_security_group.alb.id
  type              = "ingress"
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  cidr_blocks       = var.alb_ingress_cidrs
  description       = "HTTP from allowed CIDRs (redirected to HTTPS by the ALB listener, see modules/alb)."
}

resource "aws_security_group_rule" "alb_ingress_https" {
  security_group_id = aws_security_group.alb.id
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = var.alb_ingress_cidrs
  description       = "HTTPS from allowed CIDRs."
}

resource "aws_security_group_rule" "alb_egress_to_ecs" {
  security_group_id        = aws_security_group.alb.id
  type                     = "egress"
  from_port                = var.gateway_container_port
  to_port                  = var.gateway_container_port
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.ecs_tasks.id
  # AWS restricts security-group-rule descriptions to a fixed character set
  # (no em-dashes, apostrophes, or arrows are permitted, confirmed via a
  # real terraform validate failure on the original wording).
  description = "Only to gateway-service container port, the ALB never talks to any other service directly."
}

# ── ECS tasks: every one of the 8 services runs behind this one SG ─────────
#
# One shared SG (not eight per-service ones) because every service currently
# lives on the same private subnets and calls its peers directly by name —
# the AWS-side equivalent of the local docker-compose network (D56-adjacent).
# Splitting into per-service SGs with exact caller->callee port rules is a
# reasonable future tightening once M12's real task definitions pin down
# which service actually calls which — not invented speculatively here.

resource "aws_security_group" "ecs_tasks" {
  name        = "${local.name_prefix}-ecs-tasks-sg"
  description = "All 8 ECS services: ingress from the ALB (gateway-service port only) and from each other; egress unrestricted (ECR/Secrets Manager/CloudWatch/RDS/Redis/MSK all reached via the NAT-provided internet path or in-VPC)."
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${local.name_prefix}-ecs-tasks-sg" })
}

resource "aws_security_group_rule" "ecs_ingress_from_alb" {
  security_group_id        = aws_security_group.ecs_tasks.id
  type                     = "ingress"
  from_port                = var.gateway_container_port
  to_port                  = var.gateway_container_port
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.alb.id
  description              = "ALB to gateway-service only; every other service is internal-only, per the existing gateway-only-edge design (D22/D23)."
}

# Self-referencing ingress, scoped to the actual set of service ports in use
# (not a blanket all-ports-open rule) — covers every service-to-service call
# (gateway->identity/merchant/payment, payment->merchant, and every Kafka
# consumer's actuator health port).
resource "aws_security_group_rule" "ecs_ingress_from_self" {
  for_each = toset([for p in var.service_ports : tostring(p)])

  security_group_id        = aws_security_group.ecs_tasks.id
  type                     = "ingress"
  from_port                = tonumber(each.value)
  to_port                  = tonumber(each.value)
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.ecs_tasks.id
  description              = "Service-to-service call on port ${each.value}."
}

resource "aws_security_group_rule" "ecs_egress_all" {
  security_group_id = aws_security_group.ecs_tasks.id
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  description       = "ECR image pulls, Secrets Manager, CloudWatch Logs, and any external egress all route out via the NAT Gateway."
}

# ── RDS PostgreSQL ───────────────────────────────────────────────────────────

resource "aws_security_group" "rds" {
  name        = "${local.name_prefix}-rds-sg"
  description = "Postgres ingress from the ECS tasks SG only."
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${local.name_prefix}-rds-sg" })
}

resource "aws_security_group_rule" "rds_ingress_from_ecs" {
  security_group_id        = aws_security_group.rds.id
  type                     = "ingress"
  from_port                = var.rds_port
  to_port                  = var.rds_port
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.ecs_tasks.id
}

resource "aws_security_group_rule" "rds_egress_all" {
  security_group_id = aws_security_group.rds.id
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
}

# ── ElastiCache Redis ────────────────────────────────────────────────────────

resource "aws_security_group" "elasticache" {
  name        = "${local.name_prefix}-elasticache-sg"
  description = "Redis ingress from the ECS tasks SG only."
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${local.name_prefix}-elasticache-sg" })
}

resource "aws_security_group_rule" "elasticache_ingress_from_ecs" {
  security_group_id        = aws_security_group.elasticache.id
  type                     = "ingress"
  from_port                = var.redis_port
  to_port                  = var.redis_port
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.ecs_tasks.id
}

resource "aws_security_group_rule" "elasticache_egress_all" {
  security_group_id = aws_security_group.elasticache.id
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
}

# ── Kafka (self-managed, single-broker, on ECS Fargate) ─────────────────────
#
# Replaces the MSK Serverless SG — Amazon MSK's kafka:* API is blocked on
# this AWS account (see PROJECT_CONTEXT.md decision log; modules/kafka-broker
# has the full explanation). Ingress from ecs_tasks on the broker's client
# port, plus a self-referencing NFS rule (2049) so the broker's own Fargate
# task can reach its EFS-backed data volume — no other service ever touches
# this SG on 2049, only the broker task itself.

resource "aws_security_group" "kafka" {
  name        = "${local.name_prefix}-kafka-sg"
  description = "Self-managed Kafka broker: PLAINTEXT ingress from the ECS tasks SG; self-referencing NFS for its own EFS mount."
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${local.name_prefix}-kafka-sg" })
}

resource "aws_security_group_rule" "kafka_ingress_from_ecs" {
  security_group_id        = aws_security_group.kafka.id
  type                     = "ingress"
  from_port                = var.kafka_broker_port
  to_port                  = var.kafka_broker_port
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.ecs_tasks.id
}

resource "aws_security_group_rule" "kafka_efs_ingress_self" {
  security_group_id        = aws_security_group.kafka.id
  type                     = "ingress"
  from_port                = 2049
  to_port                  = 2049
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.kafka.id
  description              = "NFS so the Kafka broker task can reach its EFS-backed data volume."
}

resource "aws_security_group_rule" "kafka_egress_all" {
  security_group_id = aws_security_group.kafka.id
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
}

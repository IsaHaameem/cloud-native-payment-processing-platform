#
# The ECS Fargate cluster itself, plus a Cloud Map private DNS namespace for
# service-to-service discovery. Deliberately no task definitions or services
# here — M12's explicit job ("ECS task defs + services, ALB target groups,
# secrets injection, CD deploy") is standing up what actually runs inside
# this cluster; M11 only prepares the cluster and its discovery namespace to
# exist first.
#
# The Cloud Map namespace matters because gateway-service/payment-service
# currently call their peers by container-network DNS name
# (paymentflow.services.identity.base-uri = http://identity-service:8081,
# etc. — see docker-compose.yml). ECS Service Connect / Cloud Map is the
# direct AWS-side equivalent: M12's task definitions will register each
# service under this namespace (e.g. identity-service.paymentflow.local) so
# the exact same paymentflow.services.*.base-uri override pattern keeps
# working, just with a different DNS suffix.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

resource "aws_ecs_cluster" "this" {
  name = "${local.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = merge(var.tags, { Name = "${local.name_prefix}-cluster" })
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name = aws_ecs_cluster.this.name

  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

resource "aws_service_discovery_private_dns_namespace" "this" {
  name        = "${var.project_name}.local"
  description = "Internal service-to-service DNS for ${local.name_prefix} — one entry per service, registered by M12's task definitions."
  vpc         = var.vpc_id

  tags = merge(var.tags, { Name = "${local.name_prefix}-service-discovery" })
}

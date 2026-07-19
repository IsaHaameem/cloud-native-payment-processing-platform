#
# One reusable ECS Fargate task definition + service, instantiated once per
# microservice by the environment root (the same "one shared, parameterized
# thing instead of eight near-identical copies" philosophy as M9's Dockerfile,
# D53) — the only real per-service inputs are its name, port, image, and
# environment/secrets maps.
#
# Service Connect (not classic Cloud Map `service_registries`) provides
# internal DNS: each service registers under the M11 Cloud Map namespace
# using its own service name as the discovery name, so
# `PAYMENTFLOW_SERVICES_IDENTITY_JWKS_URI=http://identity-service:8081/...`
# resolves inside the cluster exactly like it already does against the local
# docker-compose network (M9) — no reshaping of any existing env var value,
# only a different DNS backend resolving the same hostname.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}-${var.service_name}"
  port_name   = "http"
}

resource "aws_ecs_task_definition" "this" {
  family                   = local.name_prefix
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.task_role_arn

  container_definitions = jsonencode([
    {
      name      = var.service_name
      image     = var.image
      essential = true

      portMappings = [
        {
          name          = local.port_name
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      environment = [
        for name, value in var.environment_variables : { name = name, value = value }
      ]

      secrets = [
        for name, value_from in var.secrets : { name = name, valueFrom = value_from }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = var.log_group_name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = var.service_name
        }
      }
    }
  ])

  tags = merge(var.tags, { Name = local.name_prefix })
}

resource "aws_ecs_service" "this" {
  name            = local.name_prefix
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = false
  }

  service_connect_configuration {
    enabled   = true
    namespace = var.service_connect_namespace_arn

    service {
      port_name      = local.port_name
      discovery_name = var.service_name

      client_alias {
        port     = var.container_port
        dns_name = var.service_name
      }
    }
  }

  dynamic "load_balancer" {
    for_each = var.enable_load_balancer ? [1] : []
    content {
      target_group_arn = var.target_group_arn
      container_name   = var.service_name
      container_port   = var.container_port
    }
  }

  # Only meaningful with a load balancer attached (gives a freshly-started
  # gateway-service task time to pass its actuator health check before the
  # ALB starts routing real traffic to it); harmless/unused otherwise.
  health_check_grace_period_seconds = var.enable_load_balancer ? 60 : null

  deployment_controller {
    type = "ECS"
  }

  tags = merge(var.tags, { Name = local.name_prefix })
}

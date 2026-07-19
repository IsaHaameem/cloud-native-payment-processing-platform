#
# Self-managed, single-broker Kafka (KRaft mode) on ECS Fargate — replaces
# MSK Serverless. Amazon MSK's `kafka:*` API is not enabled on this AWS
# account: confirmed via `aws kafka list-clusters` / `list-clusters-v2`
# both returning `SubscriptionRequiredException`, and — the real evidence —
# the M11/M12 `terraform apply` itself never got `aws_msk_serverless_cluster`
# into state at all, unlike every independent resource around it. This
# blocks provisioned MSK identically (same API family), so no MSK variant
# is viable on this account; see PROJECT_CONTEXT.md's decision log for the
# full comparison against provisioned MSK.
#
# Mirrors the local docker-compose Kafka broker exactly (same image, same
# KRaft single-node config, same PLAINTEXT protocol, same
# /var/lib/kafka/data path) — every topic/consumer-group name and partition
# count from M5-M8 is unaffected, and unlike MSK Serverless's IAM SASL, zero
# application code or Spring Kafka property changes are needed: every
# service is already configured for exactly this (PLAINTEXT), which also
# closes the gap M12 flagged (no service ever had aws-msk-iam-auth wired
# up) — that gap simply no longer applies.
#
# EFS gives the broker's one Fargate task persistent storage across task
# restarts/redeployments (Fargate has no attached-EBS support, only EFS).
# Secured at the network level only — the dedicated Kafka security group,
# reached from ecs_tasks on container_port and self-referencing on 2049 for
# this task's own mount — no IAM involved, matching how RDS/ElastiCache are
# already secured rather than MSK's now-removed kafka-cluster:* task-role
# policy.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}-kafka-broker"
  volume_name = "kafka-data"
  data_path   = "/var/lib/kafka/data"
  port_name   = "kafka"
}

resource "aws_efs_file_system" "this" {
  creation_token = "${local.name_prefix}-data"
  encrypted      = true

  tags = merge(var.tags, { Name = "${local.name_prefix}-data" })
}

resource "aws_efs_mount_target" "this" {
  for_each = toset(var.private_subnet_ids)

  file_system_id  = aws_efs_file_system.this.id
  subnet_id       = each.value
  security_groups = [var.security_group_id]
}

resource "aws_efs_access_point" "this" {
  file_system_id = aws_efs_file_system.this.id

  posix_user {
    uid = 0
    gid = 0
  }

  root_directory {
    path = "/kafka"
    creation_info {
      owner_uid   = 0
      owner_gid   = 0
      permissions = "0755"
    }
  }

  tags = merge(var.tags, { Name = "${local.name_prefix}-access-point" })
}

resource "aws_ecs_task_definition" "this" {
  family                   = local.name_prefix
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = var.execution_role_arn

  volume {
    name = local.volume_name

    efs_volume_configuration {
      file_system_id     = aws_efs_file_system.this.id
      transit_encryption = "ENABLED"

      authorization_config {
        access_point_id = aws_efs_access_point.this.id
        iam             = "DISABLED"
      }
    }
  }

  container_definitions = jsonencode([
    {
      name      = "kafka-broker"
      image     = var.image
      essential = true

      portMappings = [
        {
          name          = local.port_name
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      mountPoints = [
        {
          sourceVolume  = local.volume_name
          containerPath = local.data_path
          readOnly      = false
        }
      ]

      # Single-node KRaft config, translated 1:1 from docker-compose.infra.yml's
      # kafka service — only the advertised listener's host changes (kafka ->
      # kafka-broker, Service Connect's discovery name for this service).
      environment = [
        { name = "KAFKA_NODE_ID", value = "1" },
        { name = "KAFKA_PROCESS_ROLES", value = "broker,controller" },
        { name = "KAFKA_CONTROLLER_QUORUM_VOTERS", value = "1@localhost:${var.controller_port}" },
        { name = "KAFKA_CONTROLLER_LISTENER_NAMES", value = "CONTROLLER" },
        { name = "KAFKA_INTER_BROKER_LISTENER_NAME", value = "PLAINTEXT" },
        { name = "KAFKA_LISTENERS", value = "PLAINTEXT://:${var.container_port},CONTROLLER://:${var.controller_port}" },
        { name = "KAFKA_ADVERTISED_LISTENERS", value = "PLAINTEXT://kafka-broker:${var.container_port}" },
        { name = "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", value = "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT" },
        { name = "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", value = "1" },
        { name = "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", value = "1" },
        { name = "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", value = "1" },
        { name = "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", value = "0" },
        { name = "KAFKA_AUTO_CREATE_TOPICS_ENABLE", value = "false" },
        { name = "KAFKA_NUM_PARTITIONS", value = "3" },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = var.log_group_name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "kafka-broker"
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
  desired_count   = 1
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
      discovery_name = "kafka-broker"

      client_alias {
        port     = var.container_port
        dns_name = "kafka-broker"
      }
    }
  }

  deployment_controller {
    type = "ECS"
  }

  # Explicit, not just implied by the volume block referencing the same
  # filesystem: the mount targets must exist and be available before the
  # first task placement attempts to actually mount them, not merely before
  # the (already-created) filesystem resource exists.
  depends_on = [aws_efs_mount_target.this]

  tags = merge(var.tags, { Name = local.name_prefix })
}

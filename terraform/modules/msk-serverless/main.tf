#
# MSK Serverless — the user-confirmed choice over provisioned MSK (no
# per-broker minimum cost, matching this project's cost-conscious portfolio
# scale) and over self-managed Kafka on Fargate (Fargate has no attached-EBS
# support, only EFS, which is a real reliability/latency downgrade for a
# stateful broker versus the already-working local KRaft setup). IAM-based
# SASL auth only (no unauthenticated or SASL/SCRAM listener) — every ECS
# task's task role would need `kafka-cluster:*` IAM permissions scoped to
# this cluster's ARN once M12 wires up real task roles.
#
# Every consumer group name, topic name (payment.events / .retry / .dlq,
# D10), and partition count established in M5-M8 is unaffected — MSK
# Serverless auto-scales partition throughput and doesn't require
# pre-declaring partition counts the way the local single-node broker's
# KAFKA_NUM_PARTITIONS does.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

resource "aws_msk_serverless_cluster" "this" {
  cluster_name = "${local.name_prefix}-msk"

  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [var.security_group_id]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }

  tags = merge(var.tags, { Name = "${local.name_prefix}-msk" })
}

data "aws_msk_bootstrap_brokers" "this" {
  cluster_arn = aws_msk_serverless_cluster.this.arn
}

#
# One log group per service, `/ecs/<project>-<environment>-<service>` —
# M12's task definitions will point each container's `awslogs` log
# configuration at the matching group here. This is AWS-native container
# logging only; M13 ("Prometheus + Grafana + Loki... distributed tracing")
# is the platform's own application-level observability stack and is not
# duplicated or replaced by this module.
#

resource "aws_cloudwatch_log_group" "this" {
  for_each = toset(var.service_names)

  name              = "/ecs/${var.project_name}-${var.environment}-${each.key}"
  retention_in_days = var.retention_days

  tags = merge(var.tags, { Name = "${var.project_name}-${var.environment}-${each.key}-logs" })
}

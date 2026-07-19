output "bootstrap_brokers" {
  description = "PLAINTEXT bootstrap-servers string (SPRING_KAFKA_BOOTSTRAP_SERVERS equivalent) — resolves via Service Connect the same way every other internal service name already does (e.g. http://identity-service:8081)."
  value       = "kafka-broker:${var.container_port}"
}

output "service_name" {
  value = aws_ecs_service.this.name
}

output "task_definition_arn" {
  value = aws_ecs_task_definition.this.arn
}

output "efs_file_system_id" {
  value = aws_efs_file_system.this.id
}

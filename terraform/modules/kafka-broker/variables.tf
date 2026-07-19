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
  description = "Dedicated Kafka security group (ingress on container_port from ecs_tasks; self-referencing ingress on 2049 for this task's own EFS mount) — see modules/security-groups."
  type        = string
}

variable "cluster_arn" {
  type = string
}

variable "execution_role_arn" {
  description = "Reuses the platform's one ECS task execution role (ECR pull + CloudWatch Logs). This broker needs no task role at all — no Secrets Manager values, no AWS API calls of its own (see main.tf header)."
  type        = string
}

variable "log_group_name" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "service_connect_namespace_arn" {
  type = string
}

variable "image" {
  description = "Matches the local docker-compose Kafka broker (docker-compose.infra.yml) exactly — same image/version, same KRaft single-node mode, same PLAINTEXT protocol, same data directory."
  type        = string
  default     = "apache/kafka:3.9.0"
}

variable "container_port" {
  description = "PLAINTEXT client listener port every other service's SPRING_KAFKA_BOOTSTRAP_SERVERS connects to."
  type        = number
  default     = 9092
}

variable "controller_port" {
  description = "KRaft controller-quorum port — internal to this single-node broker only, never referenced by any other service or exposed through the security group."
  type        = number
  default     = 9093
}

variable "cpu" {
  description = "Fargate task-level vCPU units. Higher than the app services' 256 default (see modules/ecs-service) — a JVM-based Kafka broker needs more headroom even at this platform's low demo throughput."
  type        = number
  default     = 512
}

variable "memory" {
  type    = number
  default = 1024
}

variable "tags" {
  type    = map(string)
  default = {}
}

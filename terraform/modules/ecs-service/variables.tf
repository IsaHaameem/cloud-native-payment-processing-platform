variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "service_name" {
  description = "Exactly one of the 8 service names (e.g. \"payment-service\") — used as the ECS task/service/container name, the Cloud Map discovery name, and the awslogs stream prefix."
  type        = string
}

variable "container_port" {
  type = number
}

variable "image" {
  description = "Full image reference including tag (e.g. \"<ecr_repo_url>:latest\")."
  type        = string
}

variable "cpu" {
  description = "Fargate task-level vCPU units (256 = 0.25 vCPU). Small by default, matching this project's cost-conscious portfolio scale (see PROJECT_CONTEXT.md Risks)."
  type        = number
  default     = 256
}

variable "memory" {
  description = "Fargate task-level memory, MiB."
  type        = number
  default     = 512
}

variable "desired_count" {
  type    = number
  default = 1
}

variable "cluster_arn" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "execution_role_arn" {
  type = string
}

variable "task_role_arn" {
  type = string
}

variable "log_group_name" {
  type = string
}

variable "aws_region" {
  type = string
}

variable "environment_variables" {
  description = "Plain (non-secret) container environment variables — exactly the same SPRING_*/PAYMENTFLOW_* keys the local docker-compose.yml (M9) already sets, with AWS-hosted values substituted in by the environment root."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Container env vars resolved from Secrets Manager at task launch (via the execution role). Map key = env var name, map value = the valueFrom string (a plain secret ARN, or \"<arn>:jsonKey::\" for one field of a JSON secret)."
  type        = map(string)
  default     = {}
}

variable "service_connect_namespace_arn" {
  description = "The Cloud Map private DNS namespace (from the ecs-cluster module) this service registers itself under, and resolves its peers through."
  type        = string
}

variable "enable_load_balancer" {
  description = "true only for gateway-service — the platform's one ALB-fronted service (matches the Communication Flow: Client -> ALB -> Gateway)."
  type        = bool
  default     = false
}

variable "target_group_arn" {
  type    = string
  default = null
}

variable "tags" {
  type    = map(string)
  default = {}
}

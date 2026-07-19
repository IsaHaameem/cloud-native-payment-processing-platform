variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "vpc_id" {
  description = "VPC these security groups belong to (from the networking module)."
  type        = string
}

variable "alb_ingress_cidrs" {
  description = "CIDRs allowed to reach the ALB on 80/443. Defaults to the public internet, since the ALB is the platform's one intentional public entry point (matches the Communication Flow: Client -> ALB -> Gateway)."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "gateway_container_port" {
  description = "gateway-service's container port — the only service the ALB is allowed to reach directly (every other service is internal-only, per the existing gateway-only-edge architecture, D22/D23)."
  type        = number
  default     = 8080
}

variable "service_ports" {
  description = "Every service's container port, used to scope the self-referencing ECS-tasks ingress rule to only the ports actually in use rather than an all-ports-open rule."
  type        = list(number)
}

variable "rds_port" {
  type    = number
  default = 5432
}

variable "redis_port" {
  type    = number
  default = 6379
}

variable "msk_serverless_port" {
  description = "MSK Serverless's IAM-authenticated listener port."
  type        = number
  default     = 9098
}

variable "tags" {
  type    = map(string)
  default = {}
}

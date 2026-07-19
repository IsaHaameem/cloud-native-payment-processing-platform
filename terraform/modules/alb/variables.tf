variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "vpc_id" {
  description = "Needed for the gateway-service target group (target_type = \"ip\", required for Fargate awsvpc tasks)."
  type        = string
}

variable "gateway_container_port" {
  description = "gateway-service's container port — the ALB's only real target (M12; matches the Communication Flow: Client -> ALB -> Gateway, and modules/security-groups' own gateway_container_port)."
  type        = number
  default     = 8080
}

variable "gateway_health_check_path" {
  type    = string
  default = "/actuator/health"
}

variable "certificate_arn" {
  description = "ACM certificate ARN for the HTTPS listener. null (default) skips creating the HTTPS listener entirely — Route53/ACM issuance isn't in this milestone's scope, so M11 only prepares the ALB shell; a later milestone supplies a real certificate."
  type        = string
  default     = null
}

variable "tags" {
  type    = map(string)
  default = {}
}

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
  description = "Not /actuator/health: that endpoint folds a Redis health indicator into its aggregate and returns 503 whenever Redis blips, which would make the ALB cycle a genuinely-healthy gateway task. /health is the dedicated public liveness probe added for exactly this reason (see README.md and gateway-service's HealthController) — unauthenticated, touches no database/Redis/downstream service, answers only \"is this process serving HTTP?\"."
  type        = string
  default     = "/health"
}

variable "certificate_arn" {
  description = "ACM certificate ARN for the HTTPS listener. null (default) skips creating the HTTPS listener entirely — Route53/ACM issuance isn't in this milestone's scope, so M11 only prepares the ALB shell; a later milestone supplies a real certificate."
  type        = string
  default     = null
}

# ── agentic-commerce-service ingress (opt-in) ──────────────────────────────

variable "enable_agentic_ingress" {
  description = "When true, add a path-based listener rule that forwards `/api/agentic/*` from this same ALB to agentic-commerce-service's own target group. Agentic stays private on its container port; this is the Developer Portal's server-side proxy path (AD-8: agentic is not gateway-routed). Default false keeps the module unchanged for any caller that does not opt in."
  type        = bool
  default     = false
}

variable "agentic_container_port" {
  description = "agentic-commerce-service's container port (settings.gradle.kts / docker-compose.yml: 8095). Only used when enable_agentic_ingress is true."
  type        = number
  default     = 8095
}

variable "agentic_health_check_path" {
  description = "Target-group health-check path for agentic-commerce-service. `/actuator/health` is the service's actual health endpoint (management.endpoints.web.exposure.include lists `health`, and it is permitAll in agentic's SecurityConfig). `/actuator/health/liveness` is also available (management.endpoint.health.probes.enabled: true) if the aggregate's datasource indicator ever cycles a healthy task on an RDS blip."
  type        = string
  default     = "/actuator/health"
}

variable "agentic_path_patterns" {
  description = "ALB listener-rule path patterns routed to agentic. The portal proxy always calls `/api/agentic/<something>`; both the exact prefix and the wildcard are listed so a bare `/api/agentic` also matches."
  type        = list(string)
  default     = ["/api/agentic", "/api/agentic/*"]
}

variable "agentic_listener_rule_priority" {
  description = "Priority of the `/api/agentic/*` listener rule (1-50000, lower evaluated first). The ALB has no other rules today; 100 leaves room below it. Every other path falls through to the default action (the gateway)."
  type        = number
  default     = 100
}

variable "tags" {
  type    = map(string)
  default = {}
}

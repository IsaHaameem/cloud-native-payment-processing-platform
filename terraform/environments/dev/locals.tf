locals {
  common_tags = {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  # The exact 8 services and ports established in M9's docker-compose.yml/
  # Dockerfile and M10's CI matrix — kept as the one place this environment
  # config lists them, reused by ecr/security-groups/cloudwatch below rather
  # than repeating the list in each module call.
  services = {
    gateway-service      = { port = 8080 }
    identity-service     = { port = 8081 }
    merchant-service     = { port = 8082 }
    payment-service      = { port = 8083 }
    transaction-service  = { port = 8084 }
    audit-service        = { port = 8091 }
    notification-service = { port = 8092 }
    analytics-service    = { port = 8093 }
  }

  service_names = keys(local.services)
  service_ports = [for s in local.services : s.port]
}

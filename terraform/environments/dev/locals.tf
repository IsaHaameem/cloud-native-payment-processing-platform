locals {
  common_tags = {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  # The exact 8 services and ports established in M9's docker-compose.yml/
  # Dockerfile and M10's CI matrix — kept as the one place this environment
  # config lists them, reused by ecr/security-groups/cloudwatch/ecs below
  # rather than repeating the list in each module call.
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

  rds_jdbc_url = "jdbc:postgresql://${module.rds.address}:${module.rds.port}/${module.rds.db_name}"

  # Every non-secret env var each service needs, translated 1:1 from M9's
  # docker-compose.yml container-network values to their AWS-hosted
  # equivalents. Cross-service base-uris/jwks-uris keep the exact same
  # "http://<service-name>:<port>/..." shape they already have locally —
  # Service Connect (modules/ecs-service) resolves <service-name> the same
  # way container-network DNS does, so no value needed to change shape, only
  # what resolves it.
  service_environment_variables = {
    identity-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      SPRING_KAFKA_BOOTSTRAP_SERVERS             = module.kafka_broker.bootstrap_brokers
    }
    merchant-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      SPRING_KAFKA_BOOTSTRAP_SERVERS             = module.kafka_broker.bootstrap_brokers
      SPRING_DATA_REDIS_HOST                     = module.elasticache.primary_endpoint_address
      SPRING_DATA_REDIS_PORT                     = tostring(module.elasticache.port)
      SPRING_DATA_REDIS_SSL_ENABLED              = "true"
      PAYMENTFLOW_SERVICES_IDENTITY_JWKS_URI     = "http://identity-service:${local.services["identity-service"].port}/oauth2/jwks"
    }
    payment-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      SPRING_DATA_REDIS_HOST                     = module.elasticache.primary_endpoint_address
      SPRING_DATA_REDIS_PORT                     = tostring(module.elasticache.port)
      SPRING_DATA_REDIS_SSL_ENABLED              = "true"
      SPRING_KAFKA_BOOTSTRAP_SERVERS             = module.kafka_broker.bootstrap_brokers
      PAYMENTFLOW_SERVICES_IDENTITY_JWKS_URI     = "http://identity-service:${local.services["identity-service"].port}/oauth2/jwks"
      PAYMENTFLOW_SERVICES_MERCHANT_BASE_URI     = "http://merchant-service:${local.services["merchant-service"].port}"
    }
    transaction-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      SPRING_KAFKA_BOOTSTRAP_SERVERS             = module.kafka_broker.bootstrap_brokers
    }
    audit-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      SPRING_KAFKA_BOOTSTRAP_SERVERS             = module.kafka_broker.bootstrap_brokers
    }
    notification-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      SPRING_KAFKA_BOOTSTRAP_SERVERS             = module.kafka_broker.bootstrap_brokers
    }
    analytics-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      SPRING_KAFKA_BOOTSTRAP_SERVERS             = module.kafka_broker.bootstrap_brokers
    }
    gateway-service = {
      SPRING_PROFILES_ACTIVE         = "local"
      SPRING_KAFKA_BOOTSTRAP_SERVERS = module.kafka_broker.bootstrap_brokers
      SPRING_DATA_REDIS_HOST         = module.elasticache.primary_endpoint_address
      SPRING_DATA_REDIS_PORT         = tostring(module.elasticache.port)
      SPRING_DATA_REDIS_SSL_ENABLED  = "true"
      # Every /v1/* and /api/v1/* route the gateway declares points at one of these,
      # resolved by Service Connect the same way the local compose network resolves
      # the identical hostnames. Identity/merchant/payment were the original three;
      # sandbox/notification/transaction/audit/analytics were declared as routes
      # (M17-M20) but never given their base-uri override here, so the gateway proxied
      # them to their compile-time localhost default and every call to them failed.
      PAYMENTFLOW_SERVICES_IDENTITY_BASE_URI     = "http://identity-service:${local.services["identity-service"].port}"
      PAYMENTFLOW_SERVICES_MERCHANT_BASE_URI     = "http://merchant-service:${local.services["merchant-service"].port}"
      PAYMENTFLOW_SERVICES_PAYMENT_BASE_URI      = "http://payment-service:${local.services["payment-service"].port}"
      PAYMENTFLOW_SERVICES_SANDBOX_BASE_URI      = "http://sandbox-service:${local.services["sandbox-service"].port}"
      PAYMENTFLOW_SERVICES_NOTIFICATION_BASE_URI = "http://notification-service:${local.services["notification-service"].port}"
      PAYMENTFLOW_SERVICES_TRANSACTION_BASE_URI  = "http://transaction-service:${local.services["transaction-service"].port}"
      PAYMENTFLOW_SERVICES_AUDIT_BASE_URI        = "http://audit-service:${local.services["audit-service"].port}"
      PAYMENTFLOW_SERVICES_ANALYTICS_BASE_URI    = "http://analytics-service:${local.services["analytics-service"].port}"
      # Browser CORS allow-list (paymentflow.gateway.cors.allowed-origins). The portal's
      # own data path is server-side and never CORS-checked; this is for browser-direct
      # callers and must include the Developer Portal origin once it is deployed.
      PAYMENTFLOW_GATEWAY_CORS_ALLOWED_ORIGINS = join(",", var.gateway_cors_allowed_origins)
    }
  }

  # Every secret-backed env var each service needs, as ECS "valueFrom"
  # strings: a plain secret ARN for a single-value secret (redis auth token),
  # or "<arn>:<jsonKey>::" to pull one field out of a JSON secret (RDS
  # credentials, the JWT signing keypair) — resolved by the execution role
  # at task launch, never written into a task definition or state as a raw
  # value (D68).
  rds_username_secret = "${module.secrets.rds_master_credentials_secret_arn}:username::"
  rds_password_secret = "${module.secrets.rds_master_credentials_secret_arn}:password::"

  rds_credentials_secrets = {
    SPRING_DATASOURCE_USERNAME = local.rds_username_secret
    SPRING_DATASOURCE_PASSWORD = local.rds_password_secret
  }

  service_secrets = {
    identity-service = merge(local.rds_credentials_secrets, {
      PAYMENTFLOW_SECURITY_JWT_PRIVATE_KEY = "${module.secrets.jwt_signing_key_secret_arn}:private_key_pem::"
      PAYMENTFLOW_SECURITY_JWT_PUBLIC_KEY  = "${module.secrets.jwt_signing_key_secret_arn}:public_key_pem::"
    })
    merchant-service = merge(local.rds_credentials_secrets, {
      SPRING_DATA_REDIS_PASSWORD = module.secrets.redis_auth_token_secret_arn
    })
    payment-service = merge(local.rds_credentials_secrets, {
      SPRING_DATA_REDIS_PASSWORD = module.secrets.redis_auth_token_secret_arn
    })
    transaction-service  = local.rds_credentials_secrets
    audit-service        = local.rds_credentials_secrets
    notification-service = local.rds_credentials_secrets
    analytics-service    = local.rds_credentials_secrets
    gateway-service = {
      SPRING_DATA_REDIS_PASSWORD = module.secrets.redis_auth_token_secret_arn
    }
  }

  # Combines the per-service port/env/secrets into one map, so the 8 ECS
  # services can be instantiated with a single for_each over one module block
  # (main.tf) instead of 8 hand-written module blocks.
  # Env every service gets regardless of its own entry above — deployment-wide
  # decisions rather than per-service wiring.
  #
  # MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED: the OTel span exporter defaults on and
  # ships to management.opentelemetry.tracing.export.otlp.endpoint, whose committed
  # default is the local compose Tempo (localhost:4318). No Tempo is deployed here,
  # so every service was retrying a refused localhost connection every few seconds
  # and filling CloudWatch with the stack trace. Disabling the exporter (not tracing
  # itself — traceId/spanId still populate every log line via the Micrometer bridge)
  # is the correct choice for an environment with no trace backend.
  common_service_environment_variables = {
    MANAGEMENT_TRACING_EXPORT_OTLP_ENABLED = "false"
  }

  ecs_services = {
    for name, cfg in local.services : name => {
      port                  = cfg.port
      environment_variables = merge(local.common_service_environment_variables, local.service_environment_variables[name])
      secrets               = local.service_secrets[name]
      enable_load_balancer  = name == "gateway-service"
    }
  }
}

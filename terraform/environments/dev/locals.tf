locals {
  common_tags = {
    Project     = var.project_name
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  # The 10 services and ports of the current PaymentFlow monorepo (settings.gradle.kts)
  # — kept as the one place this environment config lists them, reused by
  # ecr/security-groups/cloudwatch/ecs below rather than repeating the list in each
  # module call. Originally M9/M10's 8; sandbox-service (M17) and
  # agentic-commerce-service (Project 3, post-M26) are reconciled in here too — both
  # postdate the M11/M12 Terraform that first wrote this map and were simply never
  # added to it.
  services = {
    gateway-service          = { port = 8080 }
    identity-service         = { port = 8081 }
    merchant-service         = { port = 8082 }
    payment-service          = { port = 8083 }
    transaction-service      = { port = 8084 }
    audit-service            = { port = 8091 }
    notification-service     = { port = 8092 }
    analytics-service        = { port = 8093 }
    sandbox-service          = { port = 8094 }
    agentic-commerce-service = { port = 8095 }
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
      PAYMENTFLOW_SERVICES_SANDBOX_BASE_URI      = "http://sandbox-service:${local.services["sandbox-service"].port}"
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
      PAYMENTFLOW_WEBHOOKS_SANDBOX_BASE_URI      = "http://sandbox-service:${local.services["sandbox-service"].port}"
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
    # sandbox-service (M17). No outbound service-to-service call of its own — it is
    # called BY payment-service (authorization decisions) and notification-service
    # (test-mode overrides), never a caller itself, so it needs no *_BASE_URI. It IS
    # a Kafka producer (sandbox.scheduled.events, consumed by payment-service's
    # outcome relay), which is why it gets the broker's bootstrap address like every
    # other Kafka-touching service.
    sandbox-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      SPRING_KAFKA_BOOTSTRAP_SERVERS             = module.kafka_broker.bootstrap_brokers
    }
    # agentic-commerce-service (Project 3, post-M26). No Kafka: it has no NewTopic
    # bean and no @KafkaListener anywhere in its source — confirmed, not assumed.
    # Its one platform dependency is the public gateway, reached the same
    # "http://<service-name>:<port>" way every other internal caller already
    # resolves its peers — it is an ordinary external consumer of PaymentFlow's own
    # /v1 API, holding one scoped merchant API key (PAYMENTFLOW_AGENT_API_KEY, a
    # Secrets Manager value below — it cannot be a literal here, since it doesn't
    # exist until this environment is up and a merchant has been seeded through it).
    # AGENTIC_LLM_PROVIDER/_MODEL are written out explicitly even though they match
    # the application's own compiled-in defaults (application.yaml), so the actual
    # choice is visible in this file rather than only inside the jar.
    agentic-commerce-service = {
      SPRING_DATASOURCE_URL                      = local.rds_jdbc_url
      SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = "5"
      SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE      = "1"
      PAYMENTFLOW_GATEWAY_URI                    = "http://gateway-service:${local.services["gateway-service"].port}"
      AGENTIC_LLM_PROVIDER                       = "anthropic"
      AGENTIC_LLM_MODEL                          = "claude-opus-5"
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

  # PAYMENTFLOW_INTERNAL_CONTEXT_SECRET — every one of the ten services reads this
  # (D100's HMAC trust boundary; verified directly against each service's own
  # application.yaml, not assumed). Not wired by the original M11/M12 Terraform,
  # which predates D100: every service's own committed default
  # ("dev-only-insecure-shared-secret-change-me") is what made that gap invisible —
  # nothing crashed, every service just silently agreed on the same public literal.
  internal_context_secret = {
    PAYMENTFLOW_INTERNAL_CONTEXT_SECRET = module.secrets.internal_context_secret_arn
  }

  # Every RDS-backed service's baseline. gateway-service is the one service with no
  # datasource at all (stateless; Redis-only) and is deliberately NOT built from
  # this base — see its own entry below.
  common_secrets = merge(local.rds_credentials_secrets, local.internal_context_secret)

  service_secrets = {
    identity-service = merge(local.common_secrets, {
      PAYMENTFLOW_SECURITY_JWT_PRIVATE_KEY = "${module.secrets.jwt_signing_key_secret_arn}:private_key_pem::"
      PAYMENTFLOW_SECURITY_JWT_PUBLIC_KEY  = "${module.secrets.jwt_signing_key_secret_arn}:public_key_pem::"
    })
    merchant-service = merge(local.common_secrets, {
      SPRING_DATA_REDIS_PASSWORD = module.secrets.redis_auth_token_secret_arn
    })
    payment-service = merge(local.common_secrets, {
      SPRING_DATA_REDIS_PASSWORD = module.secrets.redis_auth_token_secret_arn
    })
    transaction-service = local.common_secrets
    audit-service       = local.common_secrets
    # The one service that additionally owns webhook-signing-secret-at-rest
    # encryption (PAYMENTFLOW_WEBHOOK_SECRET_ENCRYPTION_KEY) — notification-service
    # only; verified no other service's application.yaml reads it.
    notification-service = merge(local.common_secrets, {
      PAYMENTFLOW_WEBHOOK_SECRET_ENCRYPTION_KEY = module.secrets.webhook_secret_encryption_key_secret_arn
    })
    analytics-service = local.common_secrets
    gateway-service = merge(local.internal_context_secret, {
      SPRING_DATA_REDIS_PASSWORD = module.secrets.redis_auth_token_secret_arn
    })
    sandbox-service = local.common_secrets
    # The three human-provided credentials (see modules/secrets: Terraform creates
    # the container and a placeholder empty version only — never a real value).
    agentic-commerce-service = merge(local.common_secrets, {
      PAYMENTFLOW_AGENT_API_KEY = module.secrets.agentic_platform_api_key_secret_arn
      ANTHROPIC_API_KEY         = module.secrets.agentic_anthropic_api_key_secret_arn
      OPENAI_API_KEY            = module.secrets.agentic_openai_api_key_secret_arn
    })
  }

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

  # The two ALB-fronted services. gateway-service is the platform edge (Client ->
  # ALB -> Gateway). agentic-commerce-service is fronted only for the Developer
  # Portal's server-side `/api/agentic/*` proxy (AD-8: its own target group and
  # listener rule, never routed through the gateway). Every other service stays
  # internal-only.
  alb_fronted_services = ["gateway-service", "agentic-commerce-service"]

  # Combines the per-service port/env/secrets into one map, so the ECS services can
  # be instantiated with a single for_each over one module block (main.tf) instead
  # of ten hand-written module blocks.
  ecs_services = {
    for name, cfg in local.services : name => {
      port                  = cfg.port
      environment_variables = merge(local.common_service_environment_variables, local.service_environment_variables[name])
      secrets               = local.service_secrets[name]
      enable_load_balancer  = contains(local.alb_fronted_services, name)
    }
  }
}

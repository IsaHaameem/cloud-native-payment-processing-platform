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
      SPRING_DATASOURCE_URL = local.rds_jdbc_url
    }
    merchant-service = {
      SPRING_DATASOURCE_URL                  = local.rds_jdbc_url
      SPRING_DATA_REDIS_HOST                 = module.elasticache.primary_endpoint_address
      SPRING_DATA_REDIS_PORT                 = tostring(module.elasticache.port)
      PAYMENTFLOW_SERVICES_IDENTITY_JWKS_URI = "http://identity-service:${local.services["identity-service"].port}/oauth2/jwks"
    }
    payment-service = {
      SPRING_DATASOURCE_URL                  = local.rds_jdbc_url
      SPRING_DATA_REDIS_HOST                 = module.elasticache.primary_endpoint_address
      SPRING_DATA_REDIS_PORT                 = tostring(module.elasticache.port)
      SPRING_KAFKA_BOOTSTRAP_SERVERS         = module.msk_serverless.bootstrap_brokers_sasl_iam
      PAYMENTFLOW_SERVICES_IDENTITY_JWKS_URI = "http://identity-service:${local.services["identity-service"].port}/oauth2/jwks"
      PAYMENTFLOW_SERVICES_MERCHANT_BASE_URI = "http://merchant-service:${local.services["merchant-service"].port}"
    }
    transaction-service = {
      SPRING_DATASOURCE_URL          = local.rds_jdbc_url
      SPRING_KAFKA_BOOTSTRAP_SERVERS = module.msk_serverless.bootstrap_brokers_sasl_iam
    }
    audit-service = {
      SPRING_DATASOURCE_URL          = local.rds_jdbc_url
      SPRING_KAFKA_BOOTSTRAP_SERVERS = module.msk_serverless.bootstrap_brokers_sasl_iam
    }
    notification-service = {
      SPRING_DATASOURCE_URL          = local.rds_jdbc_url
      SPRING_KAFKA_BOOTSTRAP_SERVERS = module.msk_serverless.bootstrap_brokers_sasl_iam
    }
    analytics-service = {
      SPRING_DATASOURCE_URL          = local.rds_jdbc_url
      SPRING_KAFKA_BOOTSTRAP_SERVERS = module.msk_serverless.bootstrap_brokers_sasl_iam
    }
    gateway-service = {
      SPRING_PROFILES_ACTIVE                 = "local"
      SPRING_DATA_REDIS_HOST                 = module.elasticache.primary_endpoint_address
      SPRING_DATA_REDIS_PORT                 = tostring(module.elasticache.port)
      PAYMENTFLOW_SERVICES_IDENTITY_BASE_URI = "http://identity-service:${local.services["identity-service"].port}"
      PAYMENTFLOW_SERVICES_MERCHANT_BASE_URI = "http://merchant-service:${local.services["merchant-service"].port}"
      PAYMENTFLOW_SERVICES_PAYMENT_BASE_URI  = "http://payment-service:${local.services["payment-service"].port}"
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
  ecs_services = {
    for name, cfg in local.services : name => {
      port                  = cfg.port
      environment_variables = local.service_environment_variables[name]
      secrets               = local.service_secrets[name]
      enable_load_balancer  = name == "gateway-service"
    }
  }
}

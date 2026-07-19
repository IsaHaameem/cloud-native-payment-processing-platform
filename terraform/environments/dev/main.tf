#
# Wires every module together for the "dev" environment. No resource is
# declared directly here — this file's only job is passing one module's
# outputs into the next module's inputs. Declared in actual dependency
# order: networking -> security-groups -> (ecr, secrets, ecs-cluster —
# independent of each other) -> iam (needs the ECS cluster ARN to scope its
# policies) -> (rds, elasticache, alb) -> cloudwatch -> kafka-broker (needs
# the ECS cluster/Service Connect namespace, its own security group, and its
# own CloudWatch log group) -> ecs-service (needs almost everything above,
# including kafka-broker's bootstrap-brokers output).
#

module "networking" {
  source = "../../modules/networking"

  project_name         = var.project_name
  environment          = var.environment
  vpc_cidr             = var.vpc_cidr
  availability_zones   = var.availability_zones
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  tags                 = local.common_tags
}

module "security_groups" {
  source = "../../modules/security-groups"

  project_name  = var.project_name
  environment   = var.environment
  vpc_id        = module.networking.vpc_id
  service_ports = local.service_ports
  tags          = local.common_tags
}

module "ecr" {
  source = "../../modules/ecr"

  project_name  = var.project_name
  environment   = var.environment
  service_names = local.service_names
  tags          = local.common_tags
}

module "secrets" {
  source = "../../modules/secrets"

  project_name = var.project_name
  environment  = var.environment
  tags         = local.common_tags
}

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  project_name = var.project_name
  environment  = var.environment
  vpc_id       = module.networking.vpc_id
  tags         = local.common_tags
}

module "iam" {
  source = "../../modules/iam"

  project_name        = var.project_name
  environment         = var.environment
  ecr_repository_arns = values(module.ecr.repository_arns)
  secret_arns = [
    module.secrets.rds_master_credentials_secret_arn,
    module.secrets.redis_auth_token_secret_arn,
    module.secrets.jwt_signing_key_secret_arn,
  ]
  github_repository = var.github_repository
  ecs_cluster_arn   = module.ecs_cluster.cluster_arn
  tags              = local.common_tags
}

module "rds" {
  source = "../../modules/rds"

  project_name       = var.project_name
  environment        = var.environment
  private_subnet_ids = module.networking.private_subnet_ids_list
  security_group_id  = module.security_groups.rds_security_group_id
  master_username    = module.secrets.rds_master_username
  master_password    = module.secrets.rds_master_password
  tags               = local.common_tags
}

module "elasticache" {
  source = "../../modules/elasticache"

  project_name       = var.project_name
  environment        = var.environment
  private_subnet_ids = module.networking.private_subnet_ids_list
  security_group_id  = module.security_groups.elasticache_security_group_id
  auth_token         = module.secrets.redis_auth_token
  tags               = local.common_tags
}

module "alb" {
  source = "../../modules/alb"

  project_name           = var.project_name
  environment            = var.environment
  vpc_id                 = module.networking.vpc_id
  public_subnet_ids      = module.networking.public_subnet_ids_list
  security_group_id      = module.security_groups.alb_security_group_id
  gateway_container_port = local.services["gateway-service"].port
  certificate_arn        = var.alb_certificate_arn
  tags                   = local.common_tags
}

module "cloudwatch" {
  source = "../../modules/cloudwatch"

  project_name = var.project_name
  environment  = var.environment
  # kafka-broker isn't one of the 8 app services (no ECR repo, no
  # self-ingress port in local.service_ports) but still needs its own
  # awslogs group, same shape as every other service's.
  service_names = concat(local.service_names, ["kafka-broker"])
  tags          = local.common_tags
}

# Self-managed Kafka (single-broker, KRaft) — replaces MSK Serverless, which
# this AWS account's kafka:* API blocks outright (see PROJECT_CONTEXT.md
# decision log and modules/kafka-broker's own header comment).
module "kafka_broker" {
  source = "../../modules/kafka-broker"

  project_name       = var.project_name
  environment        = var.environment
  private_subnet_ids = module.networking.private_subnet_ids_list
  security_group_id  = module.security_groups.kafka_security_group_id

  cluster_arn                   = module.ecs_cluster.cluster_arn
  service_connect_namespace_arn = module.ecs_cluster.service_discovery_namespace_arn
  execution_role_arn            = module.iam.ecs_task_execution_role_arn
  log_group_name                = module.cloudwatch.log_group_names["kafka-broker"]
  aws_region                    = var.aws_region

  tags = local.common_tags
}

# One ECS task definition + service per microservice (M12) — everything
# per-service (port, env vars, secrets, whether the ALB fronts it) comes from
# local.ecs_services, so this stays a single module block instead of 8
# hand-written ones (mirrors D53's "one shared, parameterized thing" choice).
module "ecs_services" {
  source   = "../../modules/ecs-service"
  for_each = local.ecs_services

  project_name   = var.project_name
  environment    = var.environment
  service_name   = each.key
  container_port = each.value.port
  image          = "${module.ecr.repository_urls[each.key]}:${var.image_tag}"

  cluster_arn        = module.ecs_cluster.cluster_arn
  private_subnet_ids = module.networking.private_subnet_ids_list
  security_group_id  = module.security_groups.ecs_tasks_security_group_id
  execution_role_arn = module.iam.ecs_task_execution_role_arn
  task_role_arn      = module.iam.ecs_task_role_arn
  log_group_name     = module.cloudwatch.log_group_names[each.key]
  aws_region         = var.aws_region

  environment_variables = each.value.environment_variables
  secrets               = each.value.secrets

  service_connect_namespace_arn = module.ecs_cluster.service_discovery_namespace_arn

  enable_load_balancer = each.value.enable_load_balancer
  target_group_arn     = each.value.enable_load_balancer ? module.alb.gateway_target_group_arn : null

  tags = local.common_tags
}

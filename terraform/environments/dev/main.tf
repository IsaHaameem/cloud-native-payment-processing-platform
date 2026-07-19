#
# Wires every module together for the "dev" environment. No resource is
# declared directly here — this file's only job is passing one module's
# outputs into the next module's inputs, matching the dependency order the
# architecture itself already implies: networking -> security-groups ->
# (ecr, secrets) -> iam -> (rds, elasticache, msk-serverless, alb,
# ecs-cluster, cloudwatch).
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

module "msk_serverless" {
  source = "../../modules/msk-serverless"

  project_name       = var.project_name
  environment        = var.environment
  private_subnet_ids = module.networking.private_subnet_ids_list
  security_group_id  = module.security_groups.msk_serverless_security_group_id
  tags               = local.common_tags
}

module "alb" {
  source = "../../modules/alb"

  project_name      = var.project_name
  environment       = var.environment
  public_subnet_ids = module.networking.public_subnet_ids_list
  security_group_id = module.security_groups.alb_security_group_id
  certificate_arn   = var.alb_certificate_arn
  tags              = local.common_tags
}

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  project_name = var.project_name
  environment  = var.environment
  vpc_id       = module.networking.vpc_id
  tags         = local.common_tags
}

module "cloudwatch" {
  source = "../../modules/cloudwatch"

  project_name  = var.project_name
  environment   = var.environment
  service_names = local.service_names
  tags          = local.common_tags
}

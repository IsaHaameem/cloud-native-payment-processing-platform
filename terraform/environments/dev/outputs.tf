output "vpc_id" {
  value = module.networking.vpc_id
}

output "public_subnet_ids" {
  value = module.networking.public_subnet_ids
}

output "private_subnet_ids" {
  value = module.networking.private_subnet_ids
}

output "alb_dns_name" {
  value = module.alb.alb_dns_name
}

output "ecr_repository_urls" {
  value = module.ecr.repository_urls
}

output "rds_endpoint" {
  value = module.rds.endpoint
}

output "redis_primary_endpoint_address" {
  value = module.elasticache.primary_endpoint_address
}

output "msk_bootstrap_brokers_sasl_iam" {
  value = module.msk_serverless.bootstrap_brokers_sasl_iam
}

output "ecs_cluster_name" {
  value = module.ecs_cluster.cluster_name
}

output "service_discovery_namespace_name" {
  value = module.ecs_cluster.service_discovery_namespace_name
}

output "cloudwatch_log_group_names" {
  value = module.cloudwatch.log_group_names
}

output "ecs_task_execution_role_arn" {
  value = module.iam.ecs_task_execution_role_arn
}

output "ecs_task_role_arn" {
  value = module.iam.ecs_task_role_arn
}

output "github_actions_cicd_role_arn" {
  value = module.iam.github_actions_cicd_role_arn
}

output "gateway_target_group_arn" {
  value = module.alb.gateway_target_group_arn
}

output "ecs_service_names" {
  value = { for name, svc in module.ecs_services : name => svc.service_name }
}

output "ecs_task_definition_arns" {
  value = { for name, svc in module.ecs_services : name => svc.task_definition_arn }
}

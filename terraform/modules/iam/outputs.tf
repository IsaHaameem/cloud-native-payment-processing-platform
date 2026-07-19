output "ecs_task_execution_role_arn" {
  value = aws_iam_role.ecs_task_execution.arn
}

output "ecs_task_role_arn" {
  value = aws_iam_role.ecs_task.arn
}

output "github_actions_cicd_role_arn" {
  description = "Assume this from GitHub Actions (via aws-actions/configure-aws-credentials' role-to-assume input) — ECR push + ECS deploy, scoped to this one repository. Set as the AWS_ECR_PUSH_ROLE_ARN repository variable cd.yml reads (see its header comment)."
  value       = aws_iam_role.github_actions_cicd.arn
}

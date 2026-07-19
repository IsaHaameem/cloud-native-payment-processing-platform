output "ecs_task_execution_role_arn" {
  value = aws_iam_role.ecs_task_execution.arn
}

output "ecs_task_role_arn" {
  value = aws_iam_role.ecs_task.arn
}

output "github_actions_ecr_push_role_arn" {
  description = "Assume this from GitHub Actions (via aws-actions/configure-aws-credentials' role-to-assume input) once ECR push is enabled."
  value       = aws_iam_role.github_actions_ecr_push.arn
}

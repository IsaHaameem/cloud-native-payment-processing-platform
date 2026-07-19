#
# Three IAM roles: the ECS task execution role (pulls images, resolves
# Secrets Manager values into container env vars, writes CloudWatch Logs —
# used by every one of the 8 task definitions M12 will create), an ECS task
# role (assumed by the running application itself; empty today since no
# service calls an AWS API directly yet, reserved for when one does), and a
# GitHub Actions OIDC deploy role scoped to ECR push only — the natural
# continuation of M10's "design so it can later push to a registry without
# major restructuring" (D59), now that a registry (ECR) actually exists.
#

data "aws_caller_identity" "current" {}

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

# ── ECS task execution role ─────────────────────────────────────────────────

data "aws_iam_policy_document" "ecs_tasks_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_task_execution" {
  name               = "${local.name_prefix}-ecs-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json

  tags = merge(var.tags, { Name = "${local.name_prefix}-ecs-task-execution" })
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_managed" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "ecs_task_execution_secrets" {
  statement {
    sid       = "ReadPlatformSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.secret_arns
  }
}

resource "aws_iam_role_policy" "ecs_task_execution_secrets" {
  name   = "${local.name_prefix}-ecs-task-execution-secrets"
  role   = aws_iam_role.ecs_task_execution.id
  policy = data.aws_iam_policy_document.ecs_task_execution_secrets.json
}

# ── ECS task role (the running application's own AWS permissions) ──────────
#
# M11 shipped this deliberately empty ("reserved for when a real need
# exists"); M12 is that real need — MSK Serverless authenticates over IAM
# SASL, not a username/password the way RDS/ElastiCache do, so the 5
# Kafka-touching services (payment/transaction/audit/notification/analytics)
# cannot connect to Kafka at all without this. Postgres/Redis access stays
# network-level (security groups) with no IAM involved, so this policy is
# scoped to Kafka only, not broadened generally.

data "aws_iam_policy_document" "ecs_task_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_task" {
  name               = "${local.name_prefix}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_task_assume_role.json

  tags = merge(var.tags, { Name = "${local.name_prefix}-ecs-task" })
}

locals {
  # MSK topic/group ARNs are derived from the cluster ARN by swapping the
  # resource-type segment — AWS's own documented ARN shape for MSK IAM
  # policies, not a format this project invented.
  msk_topic_arn_pattern = "${replace(var.msk_cluster_arn, ":cluster/", ":topic/")}/*"
  msk_group_arn_pattern = "${replace(var.msk_cluster_arn, ":cluster/", ":group/")}/*"
}

data "aws_iam_policy_document" "ecs_task_msk" {
  statement {
    sid       = "MskConnect"
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
    resources = [var.msk_cluster_arn]
  }
  statement {
    sid = "MskTopics"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:CreateTopic",
      "kafka-cluster:ReadData",
      "kafka-cluster:WriteData",
    ]
    # payment.events / .retry / .dlq (D10) all live under this one cluster —
    # wildcarded to every topic in it rather than enumerating each literal
    # topic name, which would need updating here every time a service adds one.
    resources = [local.msk_topic_arn_pattern]
  }
  statement {
    sid       = "MskConsumerGroups"
    actions   = ["kafka-cluster:DescribeGroup", "kafka-cluster:AlterGroup"]
    resources = [local.msk_group_arn_pattern]
  }
}

resource "aws_iam_role_policy" "ecs_task_msk" {
  name   = "${local.name_prefix}-ecs-task-msk"
  role   = aws_iam_role.ecs_task.id
  policy = data.aws_iam_policy_document.ecs_task_msk.json
}

# ── GitHub Actions OIDC CI/CD role (ECR push + ECS deploy) ──────────────────
#
# Named "cicd" (not "ecr_push" as in M11/D69) because M12's cd.yml now also
# calls `aws ecs update-service` with this same role — an honest rename
# reflecting the role's genuinely expanded scope, not a cosmetic change.
# Harmless to rename outright (rather than keep the old name and add a
# second role) since nothing has ever been applied to a real AWS account.

resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # SHA1 fingerprint of the root CA in token.actions.githubusercontent.com's
  # current TLS chain — verified directly against the live endpoint while
  # writing this (`openssl s_client -showcerts`), not copied from a
  # remembered value: GitHub migrated this endpoint's certs to Let's
  # Encrypt/ISRG some time ago, so the DigiCert-chain thumbprint that older
  # tutorials still cite (and that a memorized value here would have
  # silently gotten wrong) is no longer the real chain. AWS has not
  # cryptographically enforced this value for GitHub's provider since 2023
  # (it trusts the connection via its own hardcoded root-CA list instead),
  # but the API still requires a syntactically valid 40-hex-char value here.
  thumbprint_list = ["ab9d0263244dd0326eb67015705a667e79cfe998"]

  tags = merge(var.tags, { Name = "${local.name_prefix}-github-oidc" })
}

locals {
  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"
}

data "aws_iam_policy_document" "github_actions_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # Restricts to this exact repository (any branch/PR/tag) — not the whole
    # GitHub org, and not any other repo that happens to reuse this account's
    # OIDC provider.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:*"]
    }
  }
}

resource "aws_iam_role" "github_actions_cicd" {
  name               = "${local.name_prefix}-github-actions-cicd"
  assume_role_policy = data.aws_iam_policy_document.github_actions_assume_role.json

  tags = merge(var.tags, { Name = "${local.name_prefix}-github-actions-cicd" })
}

locals {
  # aws_ecs_service ARNs follow service/<cluster-name>/<service-name> — this
  # project's ECS service names are ${name_prefix}-<service>, so wildcarding
  # under this one cluster (not "*" globally) scopes UpdateService/
  # DescribeServices to exactly the 8 services this platform actually has.
  ecs_service_arn_pattern = "${replace(var.ecs_cluster_arn, ":cluster/", ":service/")}/*"
}

data "aws_iam_policy_document" "github_actions_cicd" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # this specific action has no resource-level scoping in ECR's IAM model
  }
  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
    ]
    resources = var.ecr_repository_arns
  }
  statement {
    sid       = "EcsDeploy"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = [local.ecs_service_arn_pattern]
  }
}

resource "aws_iam_role_policy" "github_actions_cicd" {
  name   = "${local.name_prefix}-github-actions-cicd"
  role   = aws_iam_role.github_actions_cicd.id
  policy = data.aws_iam_policy_document.github_actions_cicd.json
}

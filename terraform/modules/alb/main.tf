#
# The Application Load Balancer, plus its one real target: gateway-service
# (D66/M12 — every other service stays internal-only, matching the
# Communication Flow: Client -> ALB -> Gateway). M11 shipped this module with
# only a fixed-response default action and no target group, deliberately
# deferred to M12's explicit roadmap scope ("ECS task defs + services, ALB
# target groups") — this is that deferred work being completed, not a
# redesign of M11's decision.
#
# target_type = "ip" (not "instance") because Fargate awsvpc-mode tasks are
# addressed by ENI IP, not EC2 instance ID — there is no EC2 instance to
# register.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

resource "aws_lb" "this" {
  name               = "${local.name_prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.security_group_id]
  subnets            = var.public_subnet_ids

  tags = merge(var.tags, { Name = "${local.name_prefix}-alb" })
}

resource "aws_lb_target_group" "gateway" {
  name        = "${local.name_prefix}-gateway-tg"
  port        = var.gateway_container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    path                = var.gateway_health_check_path
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
    timeout             = 5
    matcher             = "200"
  }

  tags = merge(var.tags, { Name = "${local.name_prefix}-gateway-tg" })
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}

resource "aws_lb_listener" "https" {
  count = var.certificate_arn != null ? 1 : 0

  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.gateway.arn
  }
}

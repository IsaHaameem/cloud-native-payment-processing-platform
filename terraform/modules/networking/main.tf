#
# VPC, public/private subnets (one pair per AZ), Internet Gateway, NAT
# Gateway(s), and the route tables tying them together. Every other module
# (security-groups, rds, elasticache, msk-serverless, alb, ecs-cluster)
# consumes this module's outputs rather than looking up networking details
# itself — this is the only module that owns VPC-level resources.
#

locals {
  name_prefix = "${var.project_name}-${var.environment}"

  # zipmap keys resources by AZ (not a positional index), so adding/removing an
  # AZ later doesn't force-replace unrelated subnets the way a `count`-indexed
  # list would.
  public_subnets_by_az  = zipmap(var.availability_zones, var.public_subnet_cidrs)
  private_subnets_by_az = zipmap(var.availability_zones, var.private_subnet_cidrs)

  # Which AZ(s) actually get a NAT Gateway, per the single_nat_gateway toggle.
  nat_azs = var.single_nat_gateway ? [var.availability_zones[0]] : var.availability_zones
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, {
    Name = "${local.name_prefix}-vpc"
  })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${local.name_prefix}-igw"
  })
}

# ── Public subnets (ALB, NAT Gateways) ──────────────────────────────────────

resource "aws_subnet" "public" {
  for_each = local.public_subnets_by_az

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = each.value
  map_public_ip_on_launch = true

  tags = merge(var.tags, {
    Name = "${local.name_prefix}-public-${each.key}"
    Tier = "public"
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${local.name_prefix}-public-rt"
  })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

# ── Private subnets (ECS tasks, RDS, ElastiCache, MSK Serverless) ──────────

resource "aws_subnet" "private" {
  for_each = local.private_subnets_by_az

  vpc_id            = aws_vpc.this.id
  availability_zone = each.key
  cidr_block        = each.value

  tags = merge(var.tags, {
    Name = "${local.name_prefix}-private-${each.key}"
    Tier = "private"
  })
}

# ── NAT Gateway(s) ───────────────────────────────────────────────────────────

resource "aws_eip" "nat" {
  for_each = toset(local.nat_azs)

  domain = "vpc"

  tags = merge(var.tags, {
    Name = "${local.name_prefix}-nat-eip-${each.key}"
  })
}

resource "aws_nat_gateway" "this" {
  for_each = toset(local.nat_azs)

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id

  tags = merge(var.tags, {
    Name = "${local.name_prefix}-nat-${each.key}"
  })

  depends_on = [aws_internet_gateway.this]
}

# One route table per private subnet's egress path: shared (single NAT) or
# per-AZ (one NAT each), matching var.single_nat_gateway.
resource "aws_route_table" "private" {
  for_each = var.single_nat_gateway ? { shared = local.nat_azs[0] } : zipmap(var.availability_zones, var.availability_zones)

  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${local.name_prefix}-private-rt-${each.key}"
  })
}

resource "aws_route" "private_nat" {
  for_each = aws_route_table.private

  route_table_id         = each.value.id
  destination_cidr_block = "0.0.0.0/0"
  # "shared" isn't a real AZ key into aws_nat_gateway.this, so resolve back to
  # the single NAT's actual AZ key in that case; otherwise each.key already IS
  # the AZ (zipmap(azs, azs) makes key == value for the per-AZ case).
  nat_gateway_id = aws_nat_gateway.this[var.single_nat_gateway ? local.nat_azs[0] : each.key].id
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = var.single_nat_gateway ? aws_route_table.private["shared"].id : aws_route_table.private[each.key].id
}

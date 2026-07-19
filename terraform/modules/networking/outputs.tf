output "vpc_id" {
  description = "ID of the VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "CIDR block of the VPC."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnet IDs, keyed by availability zone."
  value       = { for az, subnet in aws_subnet.public : az => subnet.id }
}

output "private_subnet_ids" {
  description = "Private subnet IDs, keyed by availability zone."
  value       = { for az, subnet in aws_subnet.private : az => subnet.id }
}

output "public_subnet_ids_list" {
  description = "Public subnet IDs as a plain list (for resources that want a list, e.g. the ALB)."
  value       = [for subnet in aws_subnet.public : subnet.id]
}

output "private_subnet_ids_list" {
  description = "Private subnet IDs as a plain list (for resources that want a list, e.g. RDS/ElastiCache subnet groups)."
  value       = [for subnet in aws_subnet.private : subnet.id]
}

output "nat_gateway_ids" {
  description = "NAT Gateway IDs actually created (one or one-per-AZ, per single_nat_gateway)."
  value       = { for az, nat in aws_nat_gateway.this : az => nat.id }
}

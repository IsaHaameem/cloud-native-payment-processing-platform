output "endpoint" {
  description = "host:port endpoint."
  value       = aws_db_instance.this.endpoint
}

output "address" {
  description = "Host only (no port) — for building a JDBC URL."
  value       = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "arn" {
  value = aws_db_instance.this.arn
}

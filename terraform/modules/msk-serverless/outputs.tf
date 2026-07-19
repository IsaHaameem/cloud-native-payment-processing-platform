output "cluster_arn" {
  value = aws_msk_serverless_cluster.this.arn
}

output "bootstrap_brokers_sasl_iam" {
  description = "IAM-authenticated bootstrap broker string (SPRING_KAFKA_BOOTSTRAP_SERVERS equivalent for M12's task definitions)."
  value       = data.aws_msk_bootstrap_brokers.this.bootstrap_brokers_sasl_iam
}

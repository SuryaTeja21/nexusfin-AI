output "public_ip" {
  description = "Public IPv4 address of the OCI Ampere A1 ARM64 VM"
  value       = oci_core_instance.nexusfin_arm_node.public_ip
}

output "api_https_url" {
  description = "Zero-cost sslip.io HTTPS URL to set as NEXUSFIN_GATEWAY_URL in Vercel"
  value       = "https://api.${oci_core_instance.nexusfin_arm_node.public_ip}.sslip.io"
}

output "auth_issuer_url" {
  description = "Zero-cost sslip.io HTTPS URL to set as NEXUSFIN_KEYCLOAK_ISSUER_URL in Vercel"
  value       = "https://auth.${oci_core_instance.nexusfin_arm_node.public_ip}.sslip.io/realms/nexusfin"
}

output "ssh_command" {
  description = "SSH command to access the ARM64 VM"
  value       = "ssh ubuntu@${oci_core_instance.nexusfin_arm_node.public_ip}"
}

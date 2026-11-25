variable "tenancy_ocid" {
  type        = string
  description = "OCI Tenancy OCID"
}

variable "user_ocid" {
  type        = string
  description = "OCI User OCID"
}

variable "fingerprint" {
  type        = string
  description = "API key fingerprint for the OCI user"
}

variable "private_key_path" {
  type        = string
  description = "Local filesystem path to the OCI API private PEM key"
}

variable "region" {
  type        = string
  description = "Home OCI region eligible for Always Free Ampere A1 compute (e.g. us-ashburn-1, ap-hyderabad-1)"
}

variable "compartment_ocid" {
  type        = string
  description = "OCI Compartment OCID (can equal tenancy_ocid for root compartment)"
}

variable "ssh_public_key" {
  type        = string
  description = "SSH public key contents injected into the Ubuntu VM"
}

variable "allowed_ssh_cidr" {
  type        = string
  description = "CIDR block allowed to SSH (port 22). Restrict to your IP (e.g. 203.0.113.10/32) in production."
  default     = "0.0.0.0/0"
}

variable "ocpus" {
  type        = number
  description = "Ampere A1 OCPU count (Always Free grants up to 4 OCPUs)"
  default     = 4
}

variable "memory_in_gbs" {
  type        = number
  description = "Ampere A1 RAM in GB (Always Free grants up to 24 GB)"
  default     = 24
}

variable "boot_volume_size_in_gbs" {
  type        = number
  description = "Boot volume size in GB (Always Free grants up to 200 GB total)"
  default     = 100
}

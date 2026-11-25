data "oci_identity_availability_domains" "ads" {
  compartment_id = var.tenancy_ocid
}

data "oci_core_images" "ubuntu_arm64" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "22.04 Minimal"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "oci_core_vcn" "nexusfin_vcn" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.0.0.0/16"]
  display_name   = "nexusfin-vcn"
  dns_label      = "nexusfinvcn"
}

resource "oci_core_internet_gateway" "nexusfin_igw" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.nexusfin_vcn.id
  display_name   = "nexusfin-igw"
  enabled        = true
}

resource "oci_core_route_table" "nexusfin_rt" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.nexusfin_vcn.id
  display_name   = "nexusfin-public-rt"

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.nexusfin_igw.id
  }
}

resource "oci_core_security_list" "nexusfin_seclist" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.nexusfin_vcn.id
  display_name   = "nexusfin-edge-seclist"

  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
  }

  # SSH access (restrict var.allowed_ssh_cidr to your workstation IP)
  ingress_security_rules {
    protocol    = "6"
    source      = var.allowed_ssh_cidr
    description = "SSH access"
    tcp_options {
      min = 22
      max = 22
    }
  }

  # HTTP (Let's Encrypt ACME HTTP-01 challenge + HTTPS redirect via Caddy)
  ingress_security_rules {
    protocol    = "6"
    source      = "0.0.0.0/0"
    description = "HTTP ACME and TLS redirect"
    tcp_options {
      min = 80
      max = 80
    }
  }

  # HTTPS (API Gateway REST + WebSocket WSS + Keycloak OIDC via Caddy)
  ingress_security_rules {
    protocol    = "6"
    source      = "0.0.0.0/0"
    description = "HTTPS edge proxy"
    tcp_options {
      min = 443
      max = 443
    }
  }
}

resource "oci_core_subnet" "nexusfin_public_subnet" {
  compartment_id    = var.compartment_ocid
  vcn_id            = oci_core_vcn.nexusfin_vcn.id
  cidr_block        = "10.0.1.0/24"
  display_name      = "nexusfin-public-subnet"
  dns_label         = "publicsub"
  route_table_id    = oci_core_route_table.nexusfin_rt.id
  security_list_ids = [oci_core_security_list.nexusfin_seclist.id]
}

resource "oci_core_instance" "nexusfin_arm_node" {
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  compartment_id      = var.compartment_ocid
  display_name        = "nexusfin-arm64-host"
  shape               = "VM.Standard.A1.Flex"

  shape_config {
    ocpus         = var.ocpus
    memory_in_gbs = var.memory_in_gbs
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.nexusfin_public_subnet.id
    assign_public_ip = true
    display_name     = "nexusfin-primary-vnic"
  }

  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu_arm64.images[0].id
    boot_volume_size_in_gbs = var.boot_volume_size_in_gbs
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = base64encode(file("${path.module}/cloud-init.yaml"))
  }
}

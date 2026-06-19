#!/usr/bin/env bash
#
# Create a Frontcache AWS EC2 instance from scratch (Ubuntu 24.04), in any region.
#
# Reproduces the kind of host Frontcache standalone runs on (e.g. i-0152698fdcb5bbcd9
# in us-east-1): an Ubuntu 24.04 instance with a key pair and a security group that
# allows inbound SSH (22), HTTP (80) and HTTPS (443).
#
# It is region-agnostic:
#   - the Ubuntu 24.04 AMI is resolved per-region from Canonical's public SSM parameter
#   - the key pair is ensured in the target region (imported from your existing PEM if
#     present, otherwise created and saved locally) — key pairs are per-region in EC2
#   - the security group is created (or reused) in the region's default VPC
#
# After it prints the public DNS name, deploy Frontcache with:
#   REMOTE_HOST=<public-dns> ./install-frontcache-server-remote.sh
#
# Requirements: awscli v2 configured with credentials that can create EC2 resources
# (ec2:RunInstances, *SecurityGroup*, *KeyPair*, DescribeInstances, ssm:GetParameter),
# plus ssh-keygen (to import an existing private key's public half).
#
# Example:
#   REGION=eu-west-1 ./create-frontcache-aws-ec2-instance.sh
#   REGION=us-east-1 INSTANCE_TYPE=t3.medium INSTANCE_NAME=frontcache-prod ./create-frontcache-aws-ec2-instance.sh
#
set -euo pipefail

# ---- configuration (override via environment) --------------------------------
REGION="${REGION:-us-east-1}"                       # target AWS region
INSTANCE_TYPE="${INSTANCE_TYPE:-t3.medium}"         # 4 GB RAM; JVM peaks ~750 MB
INSTANCE_NAME="${INSTANCE_NAME:-frontcache}"        # Name tag
ARCH="${ARCH:-amd64}"                               # amd64 (x86_64) or arm64
VOLUME_SIZE="${VOLUME_SIZE:-20}"                    # root EBS size in GiB
VOLUME_TYPE="${VOLUME_TYPE:-gp3}"

# key pair: reused across regions via your existing private key if available
KEY_NAME="${KEY_NAME:-coins-2023}"
PEM_FILE="${PEM_FILE:-$HOME/.ssh/$KEY_NAME.pem}"

# security group
SG_NAME="${SG_NAME:-frontcache-sg}"
SG_DESC="${SG_DESC:-Frontcache standalone: SSH/HTTP/HTTPS}"

# inbound CIDRs (lock these down for production)
SSH_CIDR="${SSH_CIDR:-0.0.0.0/0}"
HTTP_CIDR="${HTTP_CIDR:-0.0.0.0/0}"
HTTPS_CIDR="${HTTPS_CIDR:-0.0.0.0/0}"
# ------------------------------------------------------------------------------

AWS=(aws --region "$REGION")

echo ">>> Region: $REGION | type: $INSTANCE_TYPE | arch: $ARCH | name: $INSTANCE_NAME"

# --- sanity: credentials -------------------------------------------------------
"${AWS[@]}" sts get-caller-identity >/dev/null

# --- resolve the latest Ubuntu 24.04 AMI for this region/arch ------------------
# Canonical publishes per-region AMI ids as public SSM parameters, so this works in
# any region without hard-coding an ami-id.
echo ">>> Resolving Ubuntu 24.04 ($ARCH) AMI in $REGION ..."
SSM_PARAM="/aws/service/canonical/ubuntu/server/24.04/stable/current/$ARCH/hvm/ebs-gp3/ami-id"
AMI_ID="$("${AWS[@]}" ssm get-parameters --names "$SSM_PARAM" \
  --query 'Parameters[0].Value' --output text)"
if [ -z "$AMI_ID" ] || [ "$AMI_ID" = "None" ]; then
  echo "ERROR: could not resolve Ubuntu 24.04 AMI from SSM ($SSM_PARAM)" >&2
  exit 1
fi
echo "    AMI: $AMI_ID"

# --- ensure the key pair exists in this region ---------------------------------
# Key pairs are region-scoped. Prefer importing the public half of an existing local
# private key so the SAME .pem works in every region; otherwise create a fresh one.
if "${AWS[@]}" ec2 describe-key-pairs --key-names "$KEY_NAME" >/dev/null 2>&1; then
  echo ">>> Key pair '$KEY_NAME' already present in $REGION (reusing)."
elif [ -f "$PEM_FILE" ]; then
  echo ">>> Importing public key of $PEM_FILE as key pair '$KEY_NAME' in $REGION ..."
  # write the derived public key to a temp file; fileb:// reads it as raw bytes,
  # which behaves consistently across awscli v1/v2 binary-format settings.
  PUB_TMP="$(mktemp)"
  trap 'rm -f "$PUB_TMP"' EXIT
  ssh-keygen -y -f "$PEM_FILE" > "$PUB_TMP"
  "${AWS[@]}" ec2 import-key-pair --key-name "$KEY_NAME" \
    --public-key-material "fileb://$PUB_TMP" >/dev/null
  echo "    imported (your existing $PEM_FILE will work for SSH)."
else
  echo ">>> Creating new key pair '$KEY_NAME' in $REGION -> $PEM_FILE ..."
  mkdir -p "$(dirname "$PEM_FILE")"
  "${AWS[@]}" ec2 create-key-pair --key-name "$KEY_NAME" \
    --query 'KeyMaterial' --output text > "$PEM_FILE"
  chmod 600 "$PEM_FILE"
  echo "    saved private key to $PEM_FILE (chmod 600)."
fi

# --- default VPC ---------------------------------------------------------------
VPC_ID="$("${AWS[@]}" ec2 describe-vpcs --filters Name=isDefault,Values=true \
  --query 'Vpcs[0].VpcId' --output text)"
if [ -z "$VPC_ID" ] || [ "$VPC_ID" = "None" ]; then
  echo "ERROR: no default VPC in $REGION. Create one or set a subnet/VPC explicitly." >&2
  exit 1
fi
echo ">>> Default VPC: $VPC_ID"

# --- ensure the security group exists with the right inbound rules -------------
SG_ID="$("${AWS[@]}" ec2 describe-security-groups \
  --filters Name=group-name,Values="$SG_NAME" Name=vpc-id,Values="$VPC_ID" \
  --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || true)"

if [ -z "$SG_ID" ] || [ "$SG_ID" = "None" ]; then
  echo ">>> Creating security group '$SG_NAME' in $VPC_ID ..."
  SG_ID="$("${AWS[@]}" ec2 create-security-group --group-name "$SG_NAME" \
    --description "$SG_DESC" --vpc-id "$VPC_ID" --query 'GroupId' --output text)"
else
  echo ">>> Reusing security group '$SG_NAME' ($SG_ID)."
fi

# add inbound rules (ignore 'already exists' duplicates)
authorize() { # port cidr
  "${AWS[@]}" ec2 authorize-security-group-ingress --group-id "$SG_ID" \
    --protocol tcp --port "$1" --cidr "$2" >/dev/null 2>&1 \
    && echo "    + allow tcp/$1 from $2" \
    || echo "    = tcp/$1 from $2 (already present)"
}
echo ">>> Ensuring inbound rules on $SG_ID ..."
authorize 22  "$SSH_CIDR"
authorize 80  "$HTTP_CIDR"
authorize 443 "$HTTPS_CIDR"

# --- launch the instance -------------------------------------------------------
echo ">>> Launching $INSTANCE_TYPE instance ..."
INSTANCE_ID="$("${AWS[@]}" ec2 run-instances \
  --image-id "$AMI_ID" \
  --instance-type "$INSTANCE_TYPE" \
  --key-name "$KEY_NAME" \
  --security-group-ids "$SG_ID" \
  --associate-public-ip-address \
  --block-device-mappings "DeviceName=/dev/sda1,Ebs={VolumeSize=$VOLUME_SIZE,VolumeType=$VOLUME_TYPE,DeleteOnTermination=true}" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$INSTANCE_NAME},{Key=app,Value=frontcache}]" \
  --query 'Instances[0].InstanceId' --output text)"
echo "    instance: $INSTANCE_ID"

echo ">>> Waiting for instance to enter 'running' ..."
"${AWS[@]}" ec2 wait instance-running --instance-ids "$INSTANCE_ID"

PUBLIC_DNS="$("${AWS[@]}" ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicDnsName' --output text)"
PUBLIC_IP="$("${AWS[@]}" ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"

cat <<DONE

>>> Done.
    Instance : $INSTANCE_ID  ($INSTANCE_TYPE, $REGION)
    AMI      : $AMI_ID (Ubuntu 24.04 $ARCH)
    Public DNS: $PUBLIC_DNS
    Public IP : $PUBLIC_IP
    SecurityGroup: $SG_NAME ($SG_ID)  [22, 80, 443]
    Key      : $KEY_NAME ($PEM_FILE)

    SSH:
      ssh -i $PEM_FILE ubuntu@$PUBLIC_DNS

    Deploy Frontcache to it:
      REMOTE_HOST=$PUBLIC_DNS PEM_FILE=$PEM_FILE ./install-frontcache-server-remote.sh

    Tear down when finished:
      aws --region $REGION ec2 terminate-instances --instance-ids $INSTANCE_ID
DONE

# jenkins-shared-library


# Jenkins CI/CD Setup Guide

## Table of Contents
- [Jenkins Server Setup](#jenkins-server-setup)
- [Jenkins Plugins](#jenkins-plugins)
- [Jenkins Credentials](#jenkins-credentials)
- [Jenkins Agent Setup](#jenkins-agent-setup)
- [Webhook Configuration](#webhook-configuration)
- [ECR Integration](#ecr-integration)
- [GitHub Repository Settings](#github-repository-settings)
- [GitHub Token Setup](#github-token-setup)
- [Shared Jenkins Library](#shared-jenkins-library)

---

## Jenkins Server Setup

```bash
# Download Jenkins repo
sudo curl -o /etc/yum.repos.d/jenkins.repo https://pkg.jenkins.io/rpm-stable/jenkins.repo
cat /etc/yum.repos.d/jenkins.repo

# Install Java and Jenkins
sudo yum install fontconfig java-21-openjdk -y
sudo yum install jenkins -y

# Start Jenkins
sudo systemctl daemon-reload
sudo systemctl enable jenkins
sudo systemctl start jenkins

# Get initial admin password
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
```

---

## Jenkins Plugins

Install the following plugins via **Manage Jenkins → Plugins**:

| Plugin | Notes |
|---|---|
| Pipeline: Stage View | Visual pipeline stage view |
| Pipeline Utility Steps | Utility steps for pipelines |
| AWS Credentials | AWS credential management |
| Pipeline: AWS Steps | AWS steps in pipeline |
| Multibranch Scan Webhook Trigger | After installing, configure the catalogue multibranch pipeline by adding `roboshop-catalogue` under **Scan by Webhook** |

---

## Jenkins Credentials

Add the following credentials via **Manage Jenkins → Credentials**:

| Credential ID | Type | Description |
|---|---|---|
| `aws-creds` | AWS Credentials | AWS access key & secret |
| `github-token` | Secret Text | GitHub Personal Access Token |
| `ssh-creds` | SSH Username with private key | SSH key for agent connection |

---

## Jenkins Agent Setup

### 1. Expand Disk Space

```bash
lsblk
sudo growpart /dev/nvme0n1 4

df -hT

sudo lvextend -L +10G /dev/RootVG/rootVol
sudo lvextend -L +10G /dev/mapper/RootVG-homeVol
sudo lvextend -L +10G /dev/mapper/RootVG-varVol

sudo xfs_growfs /
sudo xfs_growfs /var
sudo xfs_growfs /home

df -hT
```

### 2. Install Node.js

```bash
sudo su -
dnf module enable nodejs:20 -y
dnf install nodejs -y
```

### 3. Install Java

```bash
sudo yum install fontconfig java-21-openjdk -y
```

### 4. Install Docker

```bash
dnf -y install dnf-plugins-core
dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
dnf install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin -y

systemctl start docker
systemctl enable docker

sudo usermod -aG docker ec2-user
```

### 5. Install Trivy (Container Scanner)

```bash
curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh \
  | sudo sh -s -- -b /usr/local/bin v0.70.0
```

### 6. Register Agent in Jenkins

Go to **Manage Jenkins → Nodes → New Node** and provide:
- Launch method: **Via SSH**
- Host: Agent IP address
- Credentials: `ssh-creds`

---

## Webhook Configuration

### GitHub Webhook URL

```
http://<JENKINS_IP>:8080/github-webhook/
```

### Jenkins Configuration

In your Multibranch Pipeline job:
**Configure → Build Triggers → ✅ GitHub hook trigger for GITScm polling**

---

## ECR Integration

```bash
# Authenticate Docker to ECR
aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin <AWS_ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com

# Build image
docker build -t roboshop/catalogue .

# Tag image
docker tag roboshop/catalogue:latest \
  <AWS_ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com/roboshop/catalogue:latest

# Push image
docker push <AWS_ACCOUNT>.dkr.ecr.us-east-1.amazonaws.com/roboshop/catalogue:latest
```

> Replace `<AWS_ACCOUNT>` with your actual AWS account ID.

---

## GitHub Repository Settings

### Enable Dependabot

In the repository go to **Settings → Security → Enable**:
- ✅ Dependency graph
- ✅ Dependabot alerts

### Check Dependabot Alerts via API

```bash
curl -k -L \
  -H "Accept: application/vnd.github+json" \
  -H "Authorization: Bearer <GITHUB_TOKEN>" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  https://api.github.com/repos/maheshbabu22neeli/catalogue/dependabot/alerts
```

---

## GitHub Token Setup

1. Go to **GitHub → Settings → Developer Settings → Fine-grained tokens**
2. Click **Generate new token**
3. Select **All Repositories** (or specific repos)
4. Add the following permissions:

| Permission | Access |
|---|---|
| Commit statuses | Read and write |
| Dependabot alerts | Read |

5. Copy the token and add it to Jenkins as the `github-token` credential.

---

## Shared Jenkins Library

### Setup

1. Create a shared pipeline library repository (e.g., `shared-pipeline`)
2. Register it in Jenkins: **Manage Jenkins → System → Global Trusted Pipeline Libraries**
3. Add the repo URL and configure the library name

### Usage Notes

- `call` is the **default function** in Groovy — when you invoke a file by name, it automatically calls its `call()` function
- Structure your library with `vars/` directory for global variables and functions

```
shared-pipeline/
└── vars/
    ├── utils.groovy       # call() invoked as utils()
    └── deployUtils.groovy # call() invoked as deployUtils()
```

### Example `vars/utils.groovy`

```groovy
def call() {
    // default call
}

def updateCommitStatus(String state, String description, String context = 'Jenkins CI') {
    // update GitHub commit status
}
```

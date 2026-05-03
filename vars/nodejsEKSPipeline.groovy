def call(Map configMap) {
    pipeline {
        agent {
            node {
                label 'ROBOSHOP'
            }
        }
        environment {
            PROJECT = configMap.get("project")
            COMPONENT = configMap.get("component")
            APP_VERSION = ""
            AWS_ACCOUNT_ID = "204427113986"
            AWS_REGION = "us-east-1"
        }
        options {
            // disableConcurrentBuilds()
            // cancel pipeline due to timeout
            timeout(
                    time: 15,
                    unit: 'MINUTES'
            )
        }
        parameters {
            booleanParam(name: 'DEPLOY', defaultValue: false, description: 'Deployment Required?')
        }

        stages {
            stage('Read Version') {
                steps {
                    script {
                        echo "reading the version for project ==> : ${configMap.project}"

                        // Read the package.json file into a Groovy object
                        def packageJSON = readJSON file: 'package.json'

                        // Access the version field
                        def packageVersion = packageJSON.version

                        echo "Current Version: ${packageVersion}"

                        // Optionally, set it as a global environment variable
                        APP_VERSION = packageVersion

                        //sh 'printenv | sort'
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    script {
                        sh """
                           npm install
                        """
                    }
                }
            }

            stage('Unit Tests') {
                steps {
                    script {
                        def testResult = sh(script: 'npm test', returnStatus: true)
                        if (testResult != 0) {
                            utils.updateCommitStatus('failure', 'Unit tests failed', 'unit-tests')
                            error "Unit tests failed."
                        } else {
                            utils.updateCommitStatus('success', 'Unit tests passed', 'unit-tests')
                        }
                    }
                }
            }

            // by reaching this stage, we need to create a github token and add in Jenkins credentials
            stage('Dependabot Alerts Check') {
                steps {
                    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
                        script {
                            withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN_SCAN')]) {
                                def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
                                def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

                                def alertCount = sh(
                                        script: """
                                    curl -sf \
                                        -H "Authorization: Bearer \$GITHUB_TOKEN_SCAN" \
                                        -H "Accept: application/vnd.github+json" \
                                        -H "X-GitHub-Api-Version: 2022-11-28" \
                                        "https://api.github.com/repos/${repoPath}/dependabot/alerts?state=open&per_page=100" \
                                    | jq '[.[] | select(.security_vulnerability.severity == "high" or .security_vulnerability.severity == "critical")] | length'
                                """,
                                        returnStdout: true
                                ).trim()

                                if (alertCount.toInteger() > 0) {
                                    utils.updateCommitStatus('failure', "${alertCount} HIGH/CRITICAL Dependabot alert(s) detected", 'library-scan')
                                    error("Build aborted: ${alertCount} HIGH/CRITICAL Dependabot alert(s) detected. Resolve them before proceeding.")
                                }
                                utils.updateCommitStatus('success', 'Dependabot check passed — no HIGH/CRITICAL alerts', 'library-scan')
                                echo "Dependabot check passed — no HIGH or CRITICAL vulnerabilities found."
                            }
                        }
                    }
                }
            }


            stage('Build Docker Image') {
                steps {
                    withAWS(credentials: 'aws-creds', region: "${AWS_REGION}") {
                        sh """
                            docker build -t ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION} .
                        """
                    }
                }
            }

            stage('Trivy OS Scan') {
                steps {
                    script {
                        // Generate table report
                        sh """
                            trivy image \
                                --scanners vuln \
                                --pkg-types os \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-os-report.txt \
                                --exit-code 0 \
                                ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION}
                        """

                        // Print table to console
                        sh 'cat trivy-os-report.txt'

                        // Fail pipeline if vulnerabilities found
                        def scanResult = sh(
                                script: """
                                    trivy image \
                                        --scanners vuln \
                                        --pkg-types os \
                                        --severity HIGH,MEDIUM \
                                        --format table \
                                        --exit-code 1 \
                                        --quiet \
                                        ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION}
                                """,
                                returnStatus: true
                        )

                        if (scanResult != 0) {
                            utils.updateCommitStatus('failure', 'Trivy OS scan: HIGH/MEDIUM vulnerabilities found', 'trivy-scan')
                            error "🚨 Trivy found HIGH/MEDIUM OS vulnerabilities. Pipeline failed."
                        } else {
                            utils.updateCommitStatus('success', 'Trivy OS scan passed — no HIGH/MEDIUM vulnerabilities', 'trivy-scan')
                            echo "✅ No HIGH or MEDIUM OS vulnerabilities found. Pipeline continues."
                        }
                    }
                }
            }

            stage('Push Image tp ECR') {
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: "${AWS_REGION}") {
                                sh """
                                aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com
                                docker push ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${PROJECT}/${COMPONENT}:${APP_VERSION}
                            """
                            }
                            utils.updateCommitStatus('success', "Image ${APP_VERSION} pushed to ECR", 'push-image')
                        } catch (err) {
                            utils.updateCommitStatus('failure', 'Failed to push image to ECR', 'push-image')
                            throw err
                        }
                    }
                }
            }

            /*stage('Deploy') {
                when {
                    expression { params.DEPLOY == true }
                }
                steps {
                    script{
                        withAWS(region:"${AWS_REGION}",credentials:'aws-creds') {
                            sh """
                                cd helm
                                set -e
                                aws eks update-kubeconfig --region ${AWS_REGION} --name ${PROJECT}-dev
                                kubectl get nodes
                                sed -i "s/IMAGE_VERSION/${APP_VERSION}/g" values.yaml
                                helm upgrade --install ${COMPONENT} -f values-dev.yaml -n ${PROJECT} --atomic --wait --timeout=5m .
                                #kubectl apply -f ${COMPONENT}-dev.yaml
                            """
                        }
                    }
                }
            }*/

        }
        post {
            always {
                echo 'I will always says hello world'
                cleanWs()
            }
            success {
                echo 'Job Success'
            }
            failure {
                echo 'Job Failure'
            }
        }
    }
}
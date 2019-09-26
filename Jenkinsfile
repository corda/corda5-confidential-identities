@Library('existing-build-control')
import com.r3.build.BuildControl

BuildControl.killAllExistingBuildsForJob(this)

pipeline {
    agent {
        dockerfile {
            filename '.ci/Dockerfile'
        }
    }
    options { timestamps() }

    environment {
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
    }

    stages {
        stage('Unit Tests') {
            steps {
                sh "./gradlew clean test --info"
            }
        }

        stage('Integration Tests') {
            steps {
                sh "./gradlew integrationTest --info"
            }
        }
    }

    post {
        always {
            junit '**/build/test-results/**/*.xml'
        }
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}
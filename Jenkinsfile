pipeline {
    agent {
        label 'windows'
    }
    options {
        skipDefaultCheckout true
    }
    stages {
        stage('Checkout') {
            steps {
                ws('percent%2Fencoded') {
                    checkout scm
                }
            }
        }
    }
}

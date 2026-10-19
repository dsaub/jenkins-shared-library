def call(String serverName = 'SonarQube', boolean waitForQualityGate = false) {
    withSonarQubeEnv(serverName) {
        // Lee directamente el archivo sonar-project.properties del directorio actual
        sh 'sonar-scanner'
    }

    if (waitForQualityGate) {
        timeout(time: 2, unit: 'MINUTES') {
            waitForQualityGate abortPipeline: true
        }
    }
}
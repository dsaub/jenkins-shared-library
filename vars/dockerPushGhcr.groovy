def call(String imageName, String credentialsId = 'github-pat') {
    withCredentials([usernamePassword(
        credentialsId: credentialsId,
        usernameVariable: 'GH_USER',
        passwordVariable: 'GH_TOKEN'
    )]) {
        sh """
            echo "\$GH_TOKEN" | docker login ghcr.io -u "\$GH_USER" --password-stdin
            docker push ${imageName}
            docker logout ghcr.io
        """
    }
}
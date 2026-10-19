def call(String imageName, int exitCode = 0) {
    sh """
    docker run --rm \
        -v /var/run/docker.sock:/var/run/docker.sock \
        -v /tmp/trivy-cache:/root/.cache/ \
        aquasec/trivy:latest image \
        --severity HIGH,CRITICAL \
        --ignore-unfixed \
        --exit-code ${exitCode} \
        ${imageName}
    """
}
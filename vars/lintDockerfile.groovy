def call(String dockerfilePath = 'Dockerfile') {
    sh """
        docker run --rm -i \
            hadolint/hadolint hadolint --failure-threshold error - < ${dockerfilePath}
    """
}
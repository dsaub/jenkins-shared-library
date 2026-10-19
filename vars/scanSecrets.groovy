def call() {
    sh '''
        docker run --rm \
        -v "$(pwd):/path" \
        zricethezav/gitleaks:latest detect \
        --source="/path" \
        --verbose
    '''
}
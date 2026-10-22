// Sube una imagen a ghcr.io. Con mode 'sandboxing' la sube desde el Docker del sandbox de Daytona
// sandboxId (la imagen tiene que estar allí); sin mode o con 'emulation', desde el nodo actual.
def call(String imageName, String credentialsId = 'github-pat', String mode = null, String sandboxId = null) {
    if (mode != null && !(mode in ['emulation', 'sandboxing'])) {
        error("dockerPushGhcr: mode debe ser 'emulation' o 'sandboxing', no '${mode}'")
    }
    if (mode == 'sandboxing') {
        if (!sandboxId) {
            error("dockerPushGhcr: mode 'sandboxing' necesita el sandboxId que devuelve prepareDaytona()")
        }
        pushFromSandbox(imageName, credentialsId, sandboxId)
        return
    }
    if (sandboxId) {
        error("dockerPushGhcr: sandboxId solo se usa con mode 'sandboxing'")
    }
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

// La imagen y las credenciales llegan al sandbox como variables, no dentro del texto del script.
private void pushFromSandbox(String imageName, String credentialsId, String sandboxId) {
    withEnv(["MLIB_IMAGE=${imageName}"]) {
        withCredentials([usernamePassword(credentialsId: credentialsId,
                                          usernameVariable: 'GH_USER',
                                          passwordVariable: 'GH_TOKEN')]) {
            prepareDaytona.exec(sandboxId, "docker push ${imageName} (Daytona)", '''\
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
trap 'docker logout ghcr.io >/dev/null 2>&1 || true; rm -rf "$DOCKER_CONFIG"' EXIT
printf '%s' "$GH_TOKEN" | docker login ghcr.io -u "$GH_USER" --password-stdin
docker push "$MLIB_IMAGE"''', ['MLIB_IMAGE', 'GH_USER', 'GH_TOKEN'])
        }
    }
}

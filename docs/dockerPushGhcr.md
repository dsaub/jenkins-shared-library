# dockerPushGhcr

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/dockerPushGhcr.groovy`
- **Firma**: `void dockerPushGhcr(String imageName, String credentialsId = 'github-pat')`
- **Contexto**: dentro de un nodo con Docker y la imagen ya construida localmente.
- **Devuelve**: nada.

Sube una imagen local a GitHub Container Registry (`ghcr.io`).

## Uso

```groovy
sh 'docker build --ulimit core=0 -t ghcr.io/dsaub/app:latest .'
dockerPushGhcr('ghcr.io/dsaub/app:latest')
dockerPushGhcr('ghcr.io/dsaub/app:latest', 'mi-credencial-ghcr')
```

## Parámetros (posicionales)

| # | Parámetro | Tipo | Obligatorio | Por defecto | Descripción |
|---|---|---|---|---|---|
| 1 | `imageName` | `String` | sí | — | Referencia completa con etiqueta, en `ghcr.io/...` |
| 2 | `credentialsId` | `String` | no | `github-pat` | Credencial *Username with password*: usuario de GitHub + PAT con `write:packages` |

## Qué hace

Dentro de `withCredentials` (variables `GH_USER`, `GH_TOKEN`):

```sh
echo "$GH_TOKEN" | docker login ghcr.io -u "$GH_USER" --password-stdin
docker push <imageName>
docker logout ghcr.io
```

Si cualquier comando falla, el build falla.

## Limitaciones

- Solo `ghcr.io`. Sube una sola etiqueta y una sola arquitectura; para multiarquitectura usa
  [buildMultiArchImage](buildMultiArchImage.md).
- `imageName` se interpola sin validar ni entrecomillar en el `sh`: pásale solo valores de confianza.
- Usa el `DOCKER_CONFIG` del nodo: si falla `push`, no se ejecuta el `logout` y el login queda en el
  nodo; otros builds simultáneos comparten ese login.

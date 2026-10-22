# dockerPushGhcr

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/dockerPushGhcr.groovy`
- **Firma**: `void dockerPushGhcr(String imageName, String credentialsId = 'github-pat', String mode = null, String sandboxId = null)`
- **Contexto**: dentro de un nodo. Sin `mode` o con `'emulation'`: nodo con Docker y la imagen ya
  construida localmente. Con `'sandboxing'`: cualquier nodo Linux; la imagen tiene que estar en el
  Docker del sandbox.
- **Devuelve**: nada.

Sube una imagen a GitHub Container Registry (`ghcr.io`), desde el nodo o desde un sandbox de Daytona.

## Uso

```groovy
sh 'docker build --ulimit core=0 -t ghcr.io/dsaub/app:latest .'
dockerPushGhcr('ghcr.io/dsaub/app:latest')
dockerPushGhcr('ghcr.io/dsaub/app:latest', 'mi-credencial-ghcr')

// Imagen construida con QEMU en el propio nodo: igual que sin mode
dockerPushGhcr('ghcr.io/dsaub/app:amd64', 'github-pat', 'emulation')

// Imagen construida en el sandbox x86_64 de Daytona
def sandbox = prepareDaytona()
prepareDaytona.exec(sandbox, 'Build amd64', 'git clone https://github.com/dsaub/app /src && docker build --ulimit core=0 -t ghcr.io/dsaub/app:amd64 /src')
dockerPushGhcr('ghcr.io/dsaub/app:amd64', 'github-pat', 'sandboxing', sandbox)
```

## Parámetros (posicionales)

| # | Parámetro | Tipo | Obligatorio | Por defecto | Descripción |
|---|---|---|---|---|---|
| 1 | `imageName` | `String` | sí | — | Referencia completa con etiqueta, en `ghcr.io/...` |
| 2 | `credentialsId` | `String` | no | `github-pat` | Credencial *Username with password*: usuario de GitHub + PAT con `write:packages` |
| 3 | `mode` | `String` | no | `null` | `null` o `'emulation'`: push desde el nodo. `'sandboxing'`: push desde el sandbox. Otro valor → error |
| 4 | `sandboxId` | `String` | con `'sandboxing'` | `null` | ID que devuelve [`prepareDaytona()`](prepareDaytona.md). Con otro `mode` → error |

La API key de Daytona se lee de la credencial `daytona-token`.

## Qué hace

Sin `mode` o con `'emulation'`, dentro de `withCredentials` (variables `GH_USER`, `GH_TOKEN`):

```sh
echo "$GH_TOKEN" | docker login ghcr.io -u "$GH_USER" --password-stdin
docker push <imageName>
docker logout ghcr.io
```

Con `'sandboxing'`, mediante [`prepareDaytona.exec`](prepareDaytona.md#preparedaytonaexec), en el
sandbox: `docker login ghcr.io` con un `DOCKER_CONFIG` temporal, `docker push "$MLIB_IMAGE"` y, al
salir (también si falla), `docker logout` y borrado de ese directorio. La imagen y las credenciales
llegan como variables, no interpoladas.

Si cualquier comando falla, el build falla.

## Limitaciones

- Solo `ghcr.io`. Sube una sola etiqueta y una sola arquitectura; para multiarquitectura usa
  [buildMultiArchImage](buildMultiArchImage.md) (que con `mode: 'sandboxing'` ya sube desde el
  sandbox: no hace falta este paso).
- En modo nodo, `imageName` se interpola sin validar ni entrecomillar en el `sh`: pásale solo valores
  de confianza. En modo nodo usa el `DOCKER_CONFIG` del nodo: si falla `push`, no se ejecuta el
  `logout`.
- En modo sandboxing, el token de GitHub viaja al sandbox (ver
  [seguridad de prepareDaytona](prepareDaytona.md#seguridad-y-limitaciones)).

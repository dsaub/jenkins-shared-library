# prepareDaytona

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/prepareDaytona.groovy`
- **Firmas**:
  - `String prepareDaytona(Map config = [:])`: crea y prepara el sandbox
  - `void prepareDaytona.delete(String sandboxId, String credentialsId = 'daytona-token')`: lo borra
  - `void prepareDaytona.exec(String sandboxId, String label, String script, List envNames = [], String credentialsId = 'daytona-token')`: ejecuta un script en él (lo usan los demás pasos)
- **Contexto**: dentro de un nodo (usa la CLI de Daytona desde el agente).
- **Devuelve**: el **ID del sandbox** (`String`).

Crea un sandbox x86_64 de [Daytona](https://www.daytona.io) con Docker arrancado, para construir
`linux/amd64` sin emulación cuando los agentes de Jenkins son ARM. El ID se pasa a
[buildMultiArchImage](buildMultiArchImage.md) y a [dockerPushGhcr](dockerPushGhcr.md) con
`mode: 'sandboxing'`.

## Uso

```groovy
@Library('mlib') _

def sandbox

pipeline {
  agent none
  stages {
    stage('Sandbox') {
      agent { label 'arm64' }
      steps {
        script { sandbox = prepareDaytona() }
      }
    }
    stage('Imagen') {
      steps {
        script {
          buildMultiArchImage(image: 'ghcr.io/dsaub/app', credentialsId: 'ghcr',
                              mode: 'sandboxing', sandboxId: sandbox)
        }
      }
    }
  }
  post {
    always {
      node('arm64') {
        script {
          if (sandbox) { prepareDaytona.delete(sandbox) }
        }
      }
    }
  }
}
```

```groovy
prepareDaytona(snapshot: 'mlib-dind', cpu: 4, disk: 20, target: 'eu')
prepareDaytona(name: 'build-app-42', credentialsId: 'otra-daytona-key')
```

## Parámetros

| Parámetro | Tipo | Por defecto | Descripción y validación |
|---|---|---|---|
| `snapshot` | `String` | `mlib-dind` | Snapshot de Daytona del sandbox. Tiene que tener `dockerd`, buildx y ser x86_64 (ver abajo) |
| `name` | `String` | `mlib-<JOB_BASE_NAME>-<BUILD_NUMBER>` | Nombre del sandbox: letras, cifras, `.`, `_`, `-` (máx. 128) |
| `credentialsId` | `String` | `daytona-token` | Credencial Jenkins tipo **Secret text** con la API key de Daytona |
| `target` | `String` | (el de la cuenta) | Región de Daytona (`eu`, `us`) → `--target` |
| `cpu` | entero ≥ 1 | (el del snapshot) | Núcleos → `--cpu` |
| `memory` | entero ≥ 1 | (el del snapshot) | Memoria → `--memory`, en las unidades de la CLI de Daytona |
| `disk` | entero ≥ 1 | (el del snapshot) | Disco → `--disk`, en GB |

Cualquier otra clave → `prepareDaytona: parámetro desconocido: X`.

## Qué hace

1. Valida parámetros y comprueba que se ejecuta en un nodo.
2. **CLI de Daytona**: usa `daytona` del `PATH`; si no hay, la descarga una vez de
   `https://github.com/daytonaio/daytona/releases/latest/download/daytona-linux-<amd64|arm64>` (según
   `uname -m` del agente) a `${XDG_CACHE_HOME:-$HOME/.cache}/mlib/daytona-<arch>` y la reutiliza en
   builds siguientes (borra ese fichero para actualizarla). Necesita `curl`.
3. `daytona login --api-key "$MLIB_DAYTONA_TOKEN"` con un `HOME` temporal que se borra al salir: la
   API key no queda en el nodo ni choca con otros builds. La key llega por `withCredentials`, nunca
   interpolada en Groovy.
4. `daytona create --snapshot <snapshot> --name <name> [--target …] [--cpu …] [--memory …] [--disk …]`.
5. Lee el ID con `daytona info <name> -f json` (primer campo `"id"`). Si no lo encuentra, avisa y
   devuelve el nombre, que la CLI también acepta.
6. En el sandbox: comprueba que `uname -m` es `x86_64`, arranca `dockerd` si no responde (espera
   hasta 60 s; log en `/tmp/mlib-dockerd.log`), comprueba `docker buildx version` e instala `git` con
   `apk` o `apt-get` si falta.
7. Si el paso 6 falla, borra el sandbox y falla.
8. Devuelve el ID.

## prepareDaytona.delete

`daytona delete <sandboxId>`. **No hace fallar el build** si falla (avisa y dice cómo borrarlo a mano);
un abort sí se propaga. Fuera de un nodo avisa y no hace nada. Ponlo en `post { always { … } }`: los
sandboxes cuestan dinero mientras existan.

## prepareDaytona.exec

Ejecuta `script` (sh) en el sandbox con `set -e`, dentro de un subshell. Las variables locales
nombradas en `envNames` (`[A-Z_][A-Z0-9_]*`) se copian al script con su valor entrecomillado, leído
en el `sh` del agente: úsalo para secretos de `withCredentials`. El script viaja en base64 en una
sola palabra a `daytona exec <id> --timeout 0 -- sh -c '…'`, se escribe a un fichero temporal del
sandbox y se ejecuta con stdin vacío. No se fía del código de salida de `daytona exec`: el script
imprime `mlib-exit:<código>` y si no es `0` falla con
`El comando ha fallado en el sandbox <id>`.

```groovy
withCredentials([string(credentialsId: 'npm-token', variable: 'NPM_TOKEN')]) {
  prepareDaytona.exec(sandbox, 'npm whoami', 'npm whoami --//registry.npmjs.org/:_authToken="$NPM_TOKEN"', ['NPM_TOKEN'])
}
```

## Requisitos

- Cuenta de Daytona y API key en una credencial **Secret text** llamada `daytona-token`.
- Un **snapshot con Docker-in-Docker**, x86_64. Por ejemplo, una vez:
  `daytona snapshot create mlib-dind --image docker:28.3.3-dind` (la imagen tiene que llevar etiqueta
  fija, no `latest`). Si tu cuenta no permite contenedores privilegiados, Docker no arrancará:
  necesitarás un snapshot con clase de sandbox que lo permita (`--sandbox-class linux-vm`).
- Agente Linux (amd64 o arm64) con `curl`, `base64`, `grep`, `sed`, `tee` y salida a internet.

## Seguridad y limitaciones

- Los valores de `envNames` (contraseñas del registro, tokens de git) viajan **dentro del comando**
  de `daytona exec` codificados en base64: se ven en `ps` del agente mientras dura y Jenkins no los
  enmascara en esa forma. Además quedan accesibles para cualquiera con acceso al sandbox.
- Se asume que `daytona exec` junta los argumentos tras `--` en una línea de comando (como el
  `executeCommand` del SDK). Si una versión futura de la CLI los pasa tal cual, `exec` fallará con
  `El comando ha fallado en el sandbox`.
- Si `daytona info -f json` cambia de formato, se usa el nombre en vez del ID.
- La CLI descargada es la `latest` del momento de la primera descarga.

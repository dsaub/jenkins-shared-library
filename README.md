# mlib

Shared library de Jenkins con los pasos comunes de los pipelines. Se carga con:

```groovy
@Library('mlib') _
```

La documentación completa de cada paso está en [`docs/`](docs/README.md).

## Plugin para Claude Code

El plugin `jenkinsmlib` (en [`claude/jenkinsmlib`](claude/jenkinsmlib)) añade la skill `/jenkinsmlib`,
que le indica al agente dónde está la documentación de cada paso. Instalación rápida, dentro de Claude Code:

```
/plugin install jenkinsmlib --marketplace dsaub/jenkins-shared-library
```

En versiones anteriores a la 2.1.275, en dos pasos:

```
/plugin marketplace add dsaub/jenkins-shared-library
/plugin install jenkinsmlib@mlib
```

Desde un clon local: `/plugin marketplace add /ruta/a/jenkins-shared-library`. Para actualizar:
`/plugin marketplace update mlib`. Luego, en cualquier proyecto: `/jenkinsmlib` (o
`/jenkinsmlib buildMultiArchImage`).

## Pasos

| Paso | Qué hace |
|---|---|
| `buildMultiArchImage(image: …, credentialsId: …)` | Imagen multiarquitectura construida **sin emulación**: cada arquitectura en un agente de esa arquitectura |
| `checkDiskSpace(minFreeGb: 10, path: '/')` | Falla si en el nodo queda menos espacio libre del indicado |
| `cleanDocker(olderThan: '72h', danglingImages: true)` | Limpia la caché de build y las imágenes colgantes tras el build |
| `prepareDaytona()` | Crea un sandbox x86_64 de Daytona con Docker (descarga la CLI si falta) y devuelve su ID |
| `dockerPushGhcr(imageName, credentialsId = 'github-pat', mode = null, sandboxId = null)` | `docker login` en ghcr.io, `docker push` y `docker logout`, en el nodo o en el sandbox |
| `lintDockerfile(dockerfilePath = 'Dockerfile')` | Hadolint |
| `runSonar(serverName = 'SonarQube', waitForQualityGate = false)` | Análisis de SonarQube |
| `scanImage(imageName, exitCode = 0)` | Trivy (HIGH y CRITICAL) |
| `scanSecrets()` | Gitleaks |

`buildMultiArchImage`, `checkDiskSpace`, `cleanDocker` y `prepareDaytona` reciben los parámetros con nombre (un `Map`). Si pasas un parámetro que no existe,
por ejemplo por una errata, el paso falla en vez de ignorarlo.

## buildMultiArchImage

Construye una imagen para varias plataformas sin QEMU. Construir `linux/amd64` emulado en el agente
ARM llenó el disco de srv01: Node y Bun se colgaban compilando módulos nativos y QEMU dejaba volcados
de memoria de ~6 GB en la caché de build.

Lo que hace:

1. **Antes de construir nada**, comprueba que hay un agente en línea para la etiqueta de cada
   plataforma (ver [Si falta un agente](#si-falta-un-agente)).
2. En paralelo, en un agente de cada plataforma:
   - comprueba que el Docker del nodo es de esa arquitectura: si a un nodo ARM le pusieron la
     etiqueta `amd64`, falla en vez de emular sin avisar (y para las demás ramas antes de que
     construyan);
   - `checkout scm`;
   - `checkDiskSpace(minFreeGb: …)`;
   - `docker buildx build --platform <plataforma> --ulimit core=0 --provenance=false --push -t <image>:<commit>-<arch>`;
   - quita la etiqueta local que deja `--push` (si no, quedaría una imagen por commit en el nodo, que
     `cleanDocker` no borra porque tiene etiqueta);
   - `cleanDocker()`, también si el build falla.
3. Crea el manifiesto final en uno de los agentes:
   `docker buildx imagetools create -t <image>:<tag> … -t <image>:<commit> <image>:<commit>-arm64 <image>:<commit>-amd64`.
4. Devuelve la lista de referencias publicadas, por ejemplo
   `['ghcr.io/dsaub/poketeamcalculator:latest', 'ghcr.io/dsaub/poketeamcalculator:1a2b3c4d5e6f']`.

| Parámetro | Obligatorio | Por defecto | Descripción |
|---|---|---|---|
| `image` | sí | — | Repositorio en minúsculas y sin etiqueta, por ejemplo `ghcr.io/dsaub/poketeamcalculator` |
| `tags` | no | `['latest']` | Etiquetas finales del manifiesto. Siempre se añade también `<commit>` |
| `platforms` | no | `['linux/arm64', 'linux/amd64']` | |
| `agents` | no | `['linux/arm64': 'arm64', 'linux/amd64': 'amd64']` | Etiqueta de nodo de Jenkins para cada plataforma. Se combina con el valor por defecto: basta con poner las que cambian |
| `credentialsId` | sí | — | Credencial de Jenkins de tipo usuario/contraseña para el registro. En GHCR: tu usuario y un token con `write:packages` |
| `registry` | no | el host de `image` (`docker.io` si no lleva) | Registro para `docker login` |
| `dockerfile` | no | el `Dockerfile` del contexto | Se pasa como `-f`, relativo a la raíz del repositorio (como en `docker`) |
| `context` | no | `.` | Contexto del build |
| `buildArgs` | no | `[:]` | Un `--build-arg` por entrada. No pongas secretos: quedan en la imagen y en el log |
| `allowEmulation` | no | `false` | Ver [Si falta un agente](#si-falta-un-agente) |
| `minFreeGb` | no | `10` | Se pasa a `checkDiskSpace` en cada agente |
| `agentTimeoutMinutes` | no | `10` | Espera máxima por un agente, cuando no se puede saber de antemano si existe |
| `mode` | no | — | `'emulation'` (como `allowEmulation: true`) o `'sandboxing'` (`linux/amd64` en el sandbox de Daytona) |
| `sandboxId` | con `'sandboxing'` | — | El ID que devuelve `prepareDaytona()` |

Si tus agentes son solo ARM, construye `linux/amd64` en Daytona en vez de emularlo:

```groovy
def sandbox = prepareDaytona()   // en un nodo; credencial Secret text 'daytona-token'
buildMultiArchImage(image: 'ghcr.io/dsaub/app', credentialsId: 'ghcr', mode: 'sandboxing', sandboxId: sandbox)
prepareDaytona.delete(sandbox)   // en post { always { … } }
```

Detalles en [docs/prepareDaytona.md](docs/prepareDaytona.md) y
[docs/buildMultiArchImage.md](docs/buildMultiArchImage.md#modo-sandboxing).

```groovy
@Library('mlib') _

pipeline {
  agent none
  stages {
    stage('Imagen') {
      steps {
        script {
          buildMultiArchImage(
            image: 'ghcr.io/dsaub/poketeamcalculator',
            tags: ['latest'],
            credentialsId: 'ghcr'
          )
        }
      }
    }
  }
}
```

Más ejemplos:

```groovy
// Otro contexto (usa worker/Dockerfile, como docker) y un build arg
buildMultiArchImage(
  image: 'ghcr.io/dsaub/poketeamcalculator-worker',
  credentialsId: 'ghcr',
  context: 'worker',
  buildArgs: [APP_VERSION: env.BUILD_NUMBER]
)

// Las referencias publicadas sirven para los pasos siguientes
def refs = buildMultiArchImage(image: 'ghcr.io/dsaub/poketeamcalculator-web', credentialsId: 'ghcr')
echo "Publicada ${refs[0]}"
```

Detalles:

- **Llámala con `agent none`**: reserva ella sus propios nodos. Si la llamas dentro de un nodo, ese
  ejecutor se queda ocupado esperando (te avisa en el log).
- **El job tiene que leer el Jenkinsfile de SCM** (Multibranch o "Pipeline script from SCM"), porque
  cada agente hace `checkout scm`.
- **`<commit>`** son los 12 primeros caracteres del commit construido (el que da `checkout scm` o, si
  no lo da, `env.GIT_COMMIT`). Si no hay commit, se usa `BUILD_NUMBER` y se avisa en el log. Si cada
  plataforma acaba construyendo un commit distinto (un push entre los dos checkouts, en un job que no
  es Multibranch), falla antes de crear el manifiesto.
- **Las etiquetas `<commit>-<arch>` se quedan en el registro**, porque el manifiesto apunta a ellas.
- **Credenciales**: el usuario y la contraseña llegan al `sh` como variables de entorno de
  `withCredentials`, nunca interpoladas en Groovy, y el `docker login` lee la contraseña de
  `--password-stdin`. Cada login va a un `DOCKER_CONFIG` temporal, así no choca con el login/logout
  de otros builds del mismo nodo (por ejemplo, con `dockerPushGhcr`). Al salir, también si algo
  falla, se hace `docker logout` y se borra ese directorio. Antes del login se copia el
  `config.json` del nodo (proxies, otros registros) y se enlazan sus plugins de la CLI y los builders
  de buildx.
- Las etiquetas, la imagen, las plataformas y los nombres de `buildArgs` se validan, y todos los
  valores van entrecomillados a la línea de comandos.

### Si falta un agente

"Falta" quiere decir que no hay ningún nodo en línea con la etiqueta de una plataforma. Se comprueba
antes de empezar a construir:

- Con el plugin **Pipeline Utility Steps** instalado, se usa su paso `nodesByLabel` y se sabe al
  momento.
- Sin él no se puede saber de antemano (el sandbox no deja consultar los nodos), así que se pide un
  nodo de cada etiqueta con un `timeout` de `agentTimeoutMinutes` y se suelta enseguida. Si no llega,
  la etiqueta cuenta como no disponible, y el mensaje avisa de que también puede ser que los nodos
  estén ocupados. Si tus agentes suelen estar ocupados más tiempo, sube `agentTimeoutMinutes`.

Qué pasa entonces:

- Con `allowEmulation: false` (por defecto), falla diciendo qué etiqueta falta, sin haber construido
  nada.
- Con `allowEmulation: true`, construye esa plataforma emulada con QEMU en el agente de la primera
  plataforma que sí lo tenga, con `--ulimit core=0`, y deja un aviso grande en el log. Ese nodo
  necesita QEMU registrado en binfmt_misc, por ejemplo con
  `docker run --privileged --rm tonistiigi/binfmt --install amd64`.

Si no tienes agente amd64 y vas a emular siempre, en vez de esperar al timeout en cada build puedes
mandar esa plataforma directamente al agente ARM: `agents: ['linux/amd64': 'arm64'], allowEmulation: true`.

## checkDiskSpace

Comprueba el espacio libre en el nodo actual con `df --output=avail -BG` (GNU coreutils).

| Parámetro | Por defecto | Descripción |
|---|---|---|
| `minFreeGb` | `10` | GB libres mínimos |
| `path` | `/` | Ruta cuyo sistema de ficheros se mira |

- Si queda menos, falla: `Solo quedan 4 GB libres en srv01 (mínimo: 10 GB)`.
- Si no puede leer el espacio (ruta inexistente, `df` sin `--output`…), avisa en el log y sigue.
- Devuelve los GB libres, o `null` si no los ha podido leer.
- `checkDiskSpace.freeGb('/')` devuelve los GB libres sin comprobar nada.

Tiene que ejecutarse dentro de un nodo.

## cleanDocker

Libera espacio de Docker en el nodo actual al terminar el build:

- `docker builder prune -f --filter until=<olderThan>`: la caché de build que lleva más de
  `olderThan` sin usarse;
- `docker image prune -f`, si `danglingImages`: solo imágenes colgantes, las que no tienen etiqueta.

**Nunca** toca volúmenes, contenedores ni imágenes con etiqueta: nada de `-a`, `system prune` ni
`volume prune`, porque en srv01 hay servicios en producción en el mismo Docker.

| Parámetro | Por defecto | Descripción |
|---|---|---|
| `olderThan` | `72h` | Duración: `30m`, `72h`, `1h30m`… |
| `danglingImages` | `true` | Borrar también las imágenes colgantes |
| `path` | `/` | Ruta cuyo espacio libre se escribe en el log antes y después |

- **No hace fallar el build** si un comando de Docker falla: lo deja en el log y sigue. Solo falla si
  le pasas un parámetro inválido, para que una errata no deje de limpiar sin que nadie se entere.
- Escribe en el log el espacio libre antes y después.
- Limpia el Docker del nodo en el que se ejecuta: fuera de un nodo avisa y no hace nada. En un
  pipeline declarativo con `agent` a nivel de pipeline, `post { always { … } }` se ejecuta en ese
  nodo. Con `agent none`, ponlo en el `post` de la stage que tiene el agente.
- La caché del build que acaba de terminar no se borra hasta pasado `olderThan`. Por eso conviene
  `checkDiskSpace` antes de construir.

Un build de una sola arquitectura con las dos funciones:

```groovy
@Library('mlib') _

pipeline {
  agent { label 'arm64' }
  stages {
    stage('Imagen') {
      steps {
        checkDiskSpace(minFreeGb: 15)
        sh '''
          docker buildx build --ulimit core=0 --provenance=false --load \
            -t ghcr.io/dsaub/poketeamcalculator-worker:latest -f worker/Dockerfile ./worker
        '''
        dockerPushGhcr('ghcr.io/dsaub/poketeamcalculator-worker:latest')
      }
    }
  }
  post {
    always {
      cleanDocker()
    }
  }
}
```

## Builds de Docker en tus propios Jenkinsfile

Pon siempre `--ulimit core=0` en `docker build` y `docker buildx build`. El `dockerd` suele correr con
`LimitCORE=infinity`, así que dentro de los `RUN` el límite de volcados es ilimitado y cualquier
proceso que reviente, emulado o no, puede dejar un volcado de memoria dentro de la caché de build.

## Pruebas

`test/Jenkinsfile` es un pipeline para probar la librería a mano en Jenkins. Crea un job de tipo
Pipeline con "Pipeline script from SCM" apuntando a este repositorio y Script Path
`test/Jenkinsfile`. Necesita agentes con las etiquetas `arm64` y `amd64` y una credencial del registro.
Sube etiquetas de prueba a `ghcr.io/dsaub/mlib-test` (configurable). Comprueba:

- que se rechazan parámetros inválidos, incluida una etiqueta con `;`;
- `checkDiskSpace`, y que `cleanDocker` borra una imagen colgante pero respeta un volumen y una imagen
  con etiqueta;
- el build multiarquitectura nativo, y que el manifiesto tiene `linux/arm64` y `linux/amd64`. El
  Dockerfile de prueba falla si el build no lleva `--ulimit core=0`;
- que falla antes de construir si falta un agente, y que se niega a emular en un nodo mal etiquetado;
- `allowEmulation: true`, que necesita QEMU en el agente arm64 (se puede desactivar con un parámetro).

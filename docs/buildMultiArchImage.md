# buildMultiArchImage

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/buildMultiArchImage.groovy`
- **Firma**: `List<String> buildMultiArchImage(Map config)`
- **Contexto**: fuera de cualquier nodo (`agent none`). Reserva sus propios agentes.
- **Devuelve**: lista de referencias publicadas `imagen:etiqueta`, primero las de `tags` (sin
  repetidos, en el orden dado) y al final `imagen:<commit>` si no estaba ya. Ejemplo:
  `['ghcr.io/dsaub/poketeamcalculator:latest', 'ghcr.io/dsaub/poketeamcalculator:1a2b3c4d5e6f']`.

Construye y publica una imagen multiarquitectura **sin emulación**: cada plataforma se construye en un
agente de su propia arquitectura y al final se juntan en un manifiesto. Existe porque construir
`linux/amd64` emulado con QEMU en un agente ARM llenó el disco de srv01 (Node y Bun se colgaban
compilando módulos nativos y QEMU dejaba volcados de memoria de ~6 GB en la caché de build).

## Uso

```groovy
@Library('mlib') _

pipeline {
  agent none
  stages {
    stage('Imagen') {
      steps {
        script {
          def refs = buildMultiArchImage(
            image: 'ghcr.io/dsaub/poketeamcalculator',
            tags: ['latest'],
            credentialsId: 'ghcr'
          )
          echo "Publicada ${refs[0]}"
        }
      }
    }
  }
}
```

Más ejemplos:

```groovy
// Subcarpeta como contexto (usa worker/Dockerfile) y build args
buildMultiArchImage(
  image: 'ghcr.io/dsaub/poketeamcalculator-worker',
  credentialsId: 'ghcr',
  context: 'worker',
  buildArgs: [APP_VERSION: env.BUILD_NUMBER]
)

// Dockerfile en otra ruta (relativa a la raíz del repo, como en docker -f)
buildMultiArchImage(image: 'ghcr.io/dsaub/app', credentialsId: 'ghcr',
                    dockerfile: 'docker/Dockerfile.prod', context: '.')

// Varias etiquetas
buildMultiArchImage(image: 'ghcr.io/dsaub/app', credentialsId: 'ghcr',
                    tags: ['latest', "v${env.BUILD_NUMBER}"])

// Solo una plataforma
buildMultiArchImage(image: 'ghcr.io/dsaub/app', credentialsId: 'ghcr', platforms: ['linux/arm64'])

// Otras etiquetas de nodo (se combinan con las por defecto)
buildMultiArchImage(image: 'ghcr.io/dsaub/app', credentialsId: 'ghcr',
                    agents: ['linux/amd64': 'x86-builder'])

// Sin agente amd64: emular siempre en el ARM, sin esperar al timeout de comprobación
buildMultiArchImage(image: 'ghcr.io/dsaub/app', credentialsId: 'ghcr',
                    agents: ['linux/amd64': 'arm64'], allowEmulation: true)

// Docker Hub (sin host en la imagen → registry 'docker.io')
buildMultiArchImage(image: 'miusuario/app', credentialsId: 'dockerhub')
```

## Parámetros

| Parámetro | Tipo | Obligatorio | Por defecto | Descripción y validación |
|---|---|---|---|---|
| `image` | `String` | **sí** | — | Repositorio sin etiqueta ni digest, en minúsculas, máx. 255 caracteres. Puede llevar host (`ghcr.io/…`, `registro:5000/…`). Ej.: `ghcr.io/dsaub/poketeamcalculator` |
| `tags` | `List<String>` o `String` | no | `['latest']` | Etiquetas finales del manifiesto. Formato Docker: `[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}` (así se rechaza p. ej. `;`). Siempre se añade también `<commit>`. Repetidos se quitan |
| `platforms` | `List<String>` o `String` | no | `['linux/arm64', 'linux/amd64']` | Formato `os/arch` u `os/arch/variante` (`linux/arm/v7`). No puede estar vacía ni repetir sufijo |
| `agents` | `Map<String,String>` | no | `['linux/arm64': 'arm64', 'linux/amd64': 'amd64']` | Etiqueta de nodo Jenkins por plataforma. **Se combina** con el valor por defecto: basta poner las que cambian. Toda plataforma de `platforms` necesita etiqueta |
| `credentialsId` | `String` | **sí** | — | ID de credencial Jenkins tipo *Username with password*. En GHCR: usuario de GitHub + token con `write:packages` |
| `registry` | `String` | no | host de `image`, o `docker.io` si no lleva | Registro para `docker login`. El host se detecta si el primer componente lleva `.` o `:` o es `localhost` |
| `dockerfile` | `String` | no | (ninguno: Docker usa `Dockerfile` del contexto) | Se pasa como `-f`, relativo a la raíz del repositorio. No puede empezar por `-` |
| `context` | `String` | no | `.` | Contexto del build. No puede empezar por `-` |
| `buildArgs` | `Map` | no | `[:]` | Un `--build-arg NOMBRE=valor` por entrada. Nombres `[A-Za-z_][A-Za-z0-9_]*`, valores no nulos. **No pongas secretos**: quedan en la imagen y en el log |
| `allowEmulation` | `Boolean` | no | `false` | Permite construir con QEMU una plataforma sin agente nativo (ver abajo). Debe ser booleano real, no `'true'` |
| `minFreeGb` | entero | no | `10` | Se pasa a `checkDiskSpace` en cada agente antes de construir |
| `agentTimeoutMinutes` | entero ≥ 1 | no | `10` | Espera máxima por un agente cuando no se puede saber de antemano si existe |

Cualquier otra clave → error `buildMultiArchImage: parámetro desconocido 'X'`. Todos los problemas de
validación se juntan en un único `error` separados por `; `.

## Qué hace, paso a paso

1. **Valida** los parámetros. Si llama dentro de un nodo (`env.NODE_NAME` definido) avisa de que ese
   ejecutor queda ocupado esperando.
2. **Plan de agentes, antes de construir nada**: para cada etiqueta distinta comprueba que hay un nodo
   en línea:
   - con el plugin *Pipeline Utility Steps*: `nodesByLabel` (instantáneo);
   - sin él: pide un `node(label)` dentro de `timeout(agentTimeoutMinutes)` y lo suelta enseguida. Si
     no llega, cuenta como no disponible (puede ser que estén todos ocupados). Un abort del build
     antes del timeout se propaga.
   - Escribe en el log `Plan: linux/arm64 en 'arm64', linux/amd64 en 'amd64'`.
3. **En paralelo** (`parallel` con `failFast: true`), en un nodo de cada plataforma:
   1. comprueba la arquitectura real (`docker version --format '{{.Server.Arch}}'`). Si no coincide
      (nodo mal etiquetado) falla, salvo `allowEmulation`. Si no la puede leer, avisa y sigue;
   2. `checkout scm`;
   3. `checkDiskSpace(minFreeGb: …)`;
   4. login en el registro y
      `docker buildx build --platform <p> --ulimit core=0 --provenance=false --push [-f <dockerfile>] -t <image>:<commit>-<sufijo> [--build-arg …] <context>`;
   5. `docker image rm <image>:<commit>-<sufijo>` (quita la etiqueta local que deja `--push`; sin `-f`,
      errores ignorados);
   6. `cleanDocker()` en `finally`, también si el build falla.
4. Comprueba que todas las plataformas construyeron **el mismo commit**; si no, falla sin crear
   manifiesto.
5. En un nodo de la primera plataforma:
   `docker buildx imagetools create -t <image>:<tag> … -t <image>:<commit> <image>:<commit>-arm64 <image>:<commit>-amd64`.
6. Log `Publicada: …` y devuelve la lista de referencias.

`<sufijo>`: la plataforma sin el SO y con `/` → `-` (`linux/arm64` → `arm64`, `linux/arm/v7` → `arm-v7`).

`<commit>`: 12 primeros caracteres de `GIT_COMMIT` devuelto por `checkout scm` (o `env.GIT_COMMIT`).
Sin commit, usa `env.BUILD_NUMBER` y avisa.

## Si falta un agente

- `allowEmulation: false` (por defecto): falla antes de construir nada:
  `Falta agente para linux/amd64: no hay ningún nodo en línea con la etiqueta 'amd64'. Pon en línea un nodo con esa etiqueta o usa allowEmulation: true.`
- `allowEmulation: true`: construye esa plataforma con QEMU en el agente de la primera etiqueta
  disponible, con `--ulimit core=0`, y deja un aviso destacado entre asteriscos en el log. El nodo
  necesita QEMU en binfmt_misc: `docker run --privileged --rm tonistiigi/binfmt --install amd64`.
- Si no hay ningún agente disponible: `No hay ningún agente donde construir: …`.

## Credenciales y seguridad

- Usuario y contraseña llegan al `sh` como variables de entorno (`MLIB_REGISTRY_USER`,
  `MLIB_REGISTRY_PASSWORD`) de `withCredentials`; nunca se interpolan en Groovy y Jenkins las
  enmascara. `docker login` lee la contraseña por `--password-stdin`.
- Cada login usa un `DOCKER_CONFIG` temporal (`mktemp -d`): copia el `config.json` del nodo y enlaza
  `cli-plugins` y `buildx`. Al salir (`trap EXIT`, también con HUP/INT/TERM) hace `docker logout` y
  borra el directorio. No choca con logins de otros builds (p. ej. `dockerPushGhcr`).
- Imagen, etiquetas, plataformas y nombres de `buildArgs` se validan; todo argumento se entrecomilla
  para el shell.

## Requisitos

- Job que lea el Jenkinsfile de SCM (Multibranch o "Pipeline script from SCM"): cada agente hace
  `checkout scm`.
- Agentes con etiquetas `arm64` y `amd64` (o las que pongas en `agents`) con Docker + buildx y `df` de
  GNU coreutils.
- Credencial usuario/contraseña del registro.
- Opcional: plugin *Pipeline Utility Steps* (comprobación instantánea de agentes).

## Efectos secundarios

- En el registro quedan las etiquetas `<commit>-<sufijo>` (el manifiesto apunta a ellas) además de
  `tags` y `<commit>`.
- En cada agente: limpieza con `cleanDocker()` (caché de build > 72h e imágenes colgantes).

## Errores típicos

| Mensaje | Causa |
|---|---|
| `buildMultiArchImage: falta 'image'; falta 'credentialsId'` | Parámetros obligatorios ausentes |
| `imagen no válida 'X' (en minúsculas y sin etiqueta ni digest)` | `image` con mayúsculas, `:tag` o `@sha256` |
| `etiqueta no válida 'X'` | Tag con caracteres no permitidos |
| `falta la etiqueta de agente de 'linux/riscv64' en 'agents'` | Plataforma sin entrada en `agents` |
| `<nodo> (etiqueta 'amd64') es arm64: linux/amd64 se construiría emulada…` | Nodo mal etiquetado |
| `Las plataformas se han construido desde commits distintos (…)` | Push entre checkouts en un job no Multibranch |
| `Solo quedan N GB libres en …` | Lo lanza `checkDiskSpace` |

## Por qué `--ulimit core=0`

`dockerd` suele correr con `LimitCORE=infinity`, así que dentro de los `RUN` cualquier proceso que
reviente puede dejar un volcado de memoria en la caché de build. Ponlo también en tus propios
`docker build`/`docker buildx build`.

## Relacionados

[checkDiskSpace](checkDiskSpace.md), [cleanDocker](cleanDocker.md), [dockerPushGhcr](dockerPushGhcr.md).

# Documentación de mlib

Librería compartida de Jenkins **`mlib`** (repositorio `github.com/dsaub/jenkins-shared-library`).
Cada paso es un fichero `vars/<paso>.groovy` y se llama como un paso más del pipeline tras cargar la
librería:

```groovy
@Library('mlib') _            // versión por defecto
@Library('mlib@mi-rama') _    // otra rama o tag (requiere "Allow default version to be overridden")
```

| Paso | Firma | Devuelve | Necesita nodo | Doc |
|---|---|---|---|---|
| `buildMultiArchImage` | `buildMultiArchImage(Map)` | `List<String>` referencias publicadas | **No** (usar con `agent none`) | [buildMultiArchImage.md](buildMultiArchImage.md) |
| `checkDiskSpace` | `checkDiskSpace(Map)` / `checkDiskSpace.freeGb(String)` | `Integer` GB libres o `null` | Sí | [checkDiskSpace.md](checkDiskSpace.md) |
| `cleanDocker` | `cleanDocker(Map)` | nada (`null`) | Sí (fuera de nodo no hace nada) | [cleanDocker.md](cleanDocker.md) |
| `prepareDaytona` | `prepareDaytona(Map)` / `.delete(id)` / `.exec(id, label, script, envNames)` | `String` ID del sandbox | Sí | [prepareDaytona.md](prepareDaytona.md) |
| `dockerPushGhcr` | `dockerPushGhcr(String imageName, String credentialsId = 'github-pat', String mode = null, String sandboxId = null)` | nada | Sí | [dockerPushGhcr.md](dockerPushGhcr.md) |
| `lintDockerfile` | `lintDockerfile(String dockerfilePath = 'Dockerfile')` | nada | Sí | [lintDockerfile.md](lintDockerfile.md) |
| `runSonar` | `runSonar(String serverName = 'SonarQube', boolean waitForQualityGate = false)` | nada | Sí | [runSonar.md](runSonar.md) |
| `scanImage` | `scanImage(String imageName, int exitCode = 0)` | nada | Sí | [scanImage.md](scanImage.md) |
| `scanSecrets` | `scanSecrets()` | nada | Sí | [scanSecrets.md](scanSecrets.md) |

## Convenciones

- **Construir x86_64 con agentes ARM**: los pasos que construyen o suben imágenes aceptan `mode`
  (`'emulation'`: QEMU en el agente; `'sandboxing'`: en un sandbox x86_64 de Daytona) y `sandboxId`
  (el que devuelve `prepareDaytona()`). En `buildMultiArchImage` van con nombre (`mode:`,
  `sandboxId:`); en `dockerPushGhcr` son el 3.º y 4.º parámetro. La API key de Daytona está en la
  credencial *Secret text* `daytona-token`.
- **Parámetros con nombre (`Map`)**: `buildMultiArchImage`, `checkDiskSpace`, `cleanDocker`, `prepareDaytona`. Un
  parámetro desconocido (una errata) hace fallar el paso con `error(...)`, no se ignora.
- **Parámetros posicionales**: `dockerPushGhcr`, `lintDockerfile`, `runSonar`, `scanImage`,
  `scanSecrets`. No validan nada: los valores se interpolan tal cual en el `sh`.
- Todos los pasos que ejecutan Docker necesitan un agente Linux con la CLI de `docker` y acceso al
  demonio. `buildMultiArchImage` necesita además `buildx`.
- En builds propios de Docker usa siempre `--ulimit core=0` (ver [buildMultiArchImage.md](buildMultiArchImage.md#por-qué---ulimit-core0)).
- Los mensajes de error y del log están en español.

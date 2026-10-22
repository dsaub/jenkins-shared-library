---
name: jenkinsmlib
description: Documentación de la shared library de Jenkins "mlib" (@Library('mlib')). Úsala al escribir o revisar Jenkinsfiles que usen sus pasos buildMultiArchImage, checkDiskSpace, cleanDocker, dockerPushGhcr, lintDockerfile, runSonar, scanImage o scanSecrets.
---

# mlib: shared library de Jenkins

Los Jenkinsfile la cargan con `@Library('mlib') _`. Antes de usar o explicar cualquier paso, **lee su
documentación**; no inventes parámetros (los pasos con `Map` fallan ante claves desconocidas).

## Dónde está la documentación

Un `.md` por paso en la carpeta `docs/` del repositorio `dsaub/jenkins-shared-library`:

1. **Local**, si el directorio de trabajo es ese repositorio (o lo tienes clonado): `docs/README.md`
   (índice) y `docs/<paso>.md`. El código fuente está en `vars/<paso>.groovy`.
2. **Remoto** en cualquier otro caso:
   `https://raw.githubusercontent.com/dsaub/jenkins-shared-library/latest/docs/<paso>.md`
   (índice: `.../latest/docs/README.md`; fuente: `.../latest/vars/<paso>.groovy`).
   Si el repo es privado y no hay acceso web, usa `gh api repos/dsaub/jenkins-shared-library/contents/docs/<paso>.md?ref=latest -H 'Accept: application/vnd.github.raw'`.

## Pasos

| Paso | Doc |
|---|---|
| `buildMultiArchImage(image:, credentialsId:, …)` — imagen multiarch nativa, con `agent none` | `docs/buildMultiArchImage.md` |
| `checkDiskSpace(minFreeGb: 10, path: '/')` — falla si falta disco | `docs/checkDiskSpace.md` |
| `cleanDocker(olderThan: '72h', danglingImages: true)` — limpieza segura en `post` | `docs/cleanDocker.md` |
| `dockerPushGhcr(imageName, credentialsId = 'github-pat')` | `docs/dockerPushGhcr.md` |
| `lintDockerfile(dockerfilePath = 'Dockerfile')` — Hadolint | `docs/lintDockerfile.md` |
| `runSonar(serverName = 'SonarQube', waitForQualityGate = false)` | `docs/runSonar.md` |
| `scanImage(imageName, exitCode = 0)` — Trivy | `docs/scanImage.md` |
| `scanSecrets()` — Gitleaks | `docs/scanSecrets.md` |

## Reglas rápidas

- Si el usuario pasa argumentos a `/jenkinsmlib` (p. ej. un nombre de paso o una tarea), lee la doc de
  los pasos implicados y aplícala a esa tarea.
- En `docker build` propios, añade siempre `--ulimit core=0`.
- `buildMultiArchImage` va con `agent none` y necesita un job que lea el Jenkinsfile de SCM.

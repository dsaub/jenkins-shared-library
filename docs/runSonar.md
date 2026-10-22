# runSonar

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/runSonar.groovy`
- **Firma**: `void runSonar(String serverName = 'SonarQube', boolean waitForQualityGate = false)`
- **Contexto**: dentro de un nodo con `sonar-scanner` en el `PATH`.
- **Devuelve**: nada.

Lanza un análisis de SonarQube y, opcionalmente, espera al Quality Gate.

## Uso

```groovy
runSonar()                         // servidor 'SonarQube', sin esperar
runSonar('SonarQube', true)        // aborta el pipeline si no pasa el Quality Gate
runSonar('sonar-interno')
```

## Parámetros (posicionales)

| # | Parámetro | Tipo | Por defecto | Descripción |
|---|---|---|---|---|
| 1 | `serverName` | `String` | `SonarQube` | Nombre del servidor en *Manage Jenkins → System → SonarQube servers* |
| 2 | `waitForQualityGate` | `boolean` | `false` | Si `true`, espera el Quality Gate (máx. 2 min) y aborta si falla |

## Qué hace

1. `withSonarQubeEnv(serverName) { sh 'sonar-scanner' }`: inyecta URL y token del servidor.
   `sonar-scanner` lee `sonar-project.properties` del directorio actual.
2. Si `waitForQualityGate`: `timeout(2 MINUTES) { waitForQualityGate abortPipeline: true }`.

## Requisitos

- Plugin *SonarQube Scanner for Jenkins* y servidor configurado con ese nombre.
- `sonar-scanner` instalado en el agente (no lo descarga).
- `sonar-project.properties` en el directorio actual (con al menos `sonar.projectKey`).
- Para `waitForQualityGate`: webhook en SonarQube hacia `<jenkins>/sonarqube-webhook/`; si no, se
  agota el timeout de 2 minutos y el build falla.

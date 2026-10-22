# scanSecrets

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/scanSecrets.groovy`
- **Firma**: `void scanSecrets()`
- **Contexto**: dentro de un nodo con Docker, en el directorio del repositorio (tras `checkout`).
- **Devuelve**: nada.

Busca secretos filtrados (tokens, claves, contraseñas) en el repositorio con
[Gitleaks](https://github.com/gitleaks/gitleaks).

## Uso

```groovy
stage('Secretos') {
  steps {
    scanSecrets()
  }
}

dir('subproyecto') { scanSecrets() }   // escanea otro directorio
```

## Parámetros

Ninguno.

## Qué hace

```sh
docker run --rm -v "$(pwd):/path" zricethezav/gitleaks:latest detect --source="/path" --verbose
```

- Escanea el directorio actual (`pwd`), incluido **todo el historial de git** si hay `.git`.
- **Falla el build si encuentra algún secreto** (Gitleaks sale con código 1). Con `--verbose`, los
  hallazgos salen en el log: el secreto puede aparecer en claro.
- Respeta `.gitleaks.toml` y `.gitleaksignore` del repositorio.
- Usa `zricethezav/gitleaks:latest` (sin versión fija).
- En un clon superficial (shallow) solo ve los commits descargados. El contenedor corre como root:
  puede dejar ficheros de root en el workspace solo si Gitleaks escribe (con estas opciones no lo hace).

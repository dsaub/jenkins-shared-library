# lintDockerfile

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/lintDockerfile.groovy`
- **Firma**: `void lintDockerfile(String dockerfilePath = 'Dockerfile')`
- **Contexto**: dentro de un nodo con Docker y el repositorio en el workspace.
- **Devuelve**: nada.

Analiza un Dockerfile con [Hadolint](https://github.com/hadolint/hadolint).

## Uso

```groovy
lintDockerfile()                       // ./Dockerfile
lintDockerfile('worker/Dockerfile')
```

## Parámetros (posicionales)

| # | Parámetro | Tipo | Por defecto | Descripción |
|---|---|---|---|---|
| 1 | `dockerfilePath` | `String` | `Dockerfile` | Ruta relativa al directorio actual del workspace |

## Qué hace

```sh
docker run --rm -i hadolint/hadolint hadolint --failure-threshold error - < <dockerfilePath>
```

- El Dockerfile se pasa por stdin, así que no se monta nada.
- **Falla el build solo con hallazgos de nivel `error`**; `warning`, `info` y `style` salen en el log
  pero no fallan.
- Usa `hadolint/hadolint:latest` (sin versión fija).
- No lee `.hadolint.yaml` del repo (el contenedor no ve el workspace).
- La ruta se interpola sin entrecomillar: evita espacios o valores no fiables.

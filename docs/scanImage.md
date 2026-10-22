# scanImage

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/scanImage.groovy`
- **Firma**: `void scanImage(String imageName, int exitCode = 0)`
- **Contexto**: dentro de un nodo con Docker.
- **Devuelve**: nada.

Escanea vulnerabilidades de una imagen con [Trivy](https://github.com/aquasecurity/trivy).

## Uso

```groovy
scanImage('ghcr.io/dsaub/app:latest')        // solo informa
scanImage('ghcr.io/dsaub/app:latest', 1)     // falla si hay HIGH/CRITICAL con arreglo
```

## Parámetros (posicionales)

| # | Parámetro | Tipo | Obligatorio | Por defecto | Descripción |
|---|---|---|---|---|---|
| 1 | `imageName` | `String` | sí | — | Imagen a escanear (local en el Docker del nodo o en un registro accesible) |
| 2 | `exitCode` | `int` | no | `0` | Código de salida de Trivy si encuentra vulnerabilidades. `0` = nunca falla; `1` = falla el build |

## Qué hace

```sh
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /tmp/trivy-cache:/root/.cache/ \
  aquasec/trivy:latest image \
  --severity HIGH,CRITICAL --ignore-unfixed --exit-code <exitCode> <imageName>
```

- Solo severidades **HIGH** y **CRITICAL**, e ignora las que no tienen arreglo publicado.
- Monta el socket de Docker, así que puede escanear imágenes locales no subidas.
- Caché de la base de datos en `/tmp/trivy-cache` del nodo (persiste entre builds).
- Usa `aquasec/trivy:latest` (sin versión fija). Resultado en formato tabla en el log.
- `imageName` se interpola sin validar: pásale solo valores de confianza.
- Para imágenes privadas en un registro, el Docker del nodo no aporta credenciales a Trivy salvo que
  la imagen esté ya en local.

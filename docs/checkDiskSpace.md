# checkDiskSpace

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/checkDiskSpace.groovy`
- **Firmas**:
  - `Integer checkDiskSpace(Map config = [:])`
  - `Integer checkDiskSpace.freeGb(String path = '/')`
- **Contexto**: dentro de un nodo (usa `sh`).
- **Devuelve**: los GB libres (`Integer`), o `null` si no se han podido leer.

Comprueba el espacio libre del sistema de ficheros de `path` en el nodo actual y **falla el build** si
queda menos de `minFreeGb`.

## Uso

```groovy
checkDiskSpace()                          // ≥ 10 GB libres en /
checkDiskSpace(minFreeGb: 15)
checkDiskSpace(minFreeGb: 20, path: '/var/lib/docker')

script {
  Integer libres = checkDiskSpace(minFreeGb: 5)
  echo "Quedan ${libres} GB"

  // Solo leer, sin comprobar ni fallar
  Integer gb = checkDiskSpace.freeGb('/')
}
```

## Parámetros

| Parámetro | Tipo | Por defecto | Descripción y validación |
|---|---|---|---|
| `minFreeGb` | entero (0–999999) | `10` | GB libres mínimos. Acepta número o texto numérico; otra cosa → error |
| `path` | `String` | `/` | Ruta cuyo sistema de ficheros se mira. No puede estar vacía |

Cualquier otra clave → `checkDiskSpace: parámetro desconocido: X`.

`freeGb(path)` no valida nada ni falla: devuelve `Integer` o `null`.

## Qué hace

1. Valida parámetros.
2. Ejecuta `df --output=avail -BG -- "$MLIB_DF_PATH" 2>&1 | tail -n 1` (la ruta va por variable de
   entorno, no interpolada; requiere `df` de GNU coreutils).
3. Si la salida no es `<n>G` (ruta inexistente, `df` sin `--output`…): escribe
   `AVISO: no he podido leer el espacio libre en <nodo>[:<path>] (<salida>). Sigo sin comprobarlo.` y
   devuelve `null`.
4. Si `libres < minFreeGb`: `error("Solo quedan 4 GB libres en srv01 (mínimo: 10 GB)")`.
5. Si no: log `Espacio libre en srv01: 42 GB (mínimo: 10 GB)` y devuelve `42`.

Los GB se redondean hacia arriba según `df -BG`.

## Notas

- Lo usan `buildMultiArchImage` (antes de cada build) y `cleanDocker` (vía `freeGb`, para el log).
- Conviene llamarlo antes de construir: la caché del build actual no la borra `cleanDocker` hasta
  pasado `olderThan`.

## Relacionados

[cleanDocker](cleanDocker.md), [buildMultiArchImage](buildMultiArchImage.md).

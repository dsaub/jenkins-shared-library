# cleanDocker

- **Librería**: `mlib` (`@Library('mlib') _`)
- **Fichero**: `vars/cleanDocker.groovy`
- **Firma**: `void cleanDocker(Map config = [:])`
- **Contexto**: dentro de un nodo. Fuera de un nodo avisa y no hace nada.
- **Devuelve**: nada (`null`).

Libera espacio de Docker en el nodo actual al terminar el build. Pensado para
`post { always { cleanDocker() } }`.

## Uso

```groovy
pipeline {
  agent { label 'arm64' }
  stages { /* … */ }
  post {
    always {
      cleanDocker()
    }
  }
}

cleanDocker(olderThan: '24h')
cleanDocker(olderThan: '1h30m', danglingImages: false)
cleanDocker(path: '/var/lib/docker')   // ruta para medir el espacio en el log
```

Con `agent none`, ponlo en el `post` de la stage que tiene el agente.

## Parámetros

| Parámetro | Tipo | Por defecto | Descripción y validación |
|---|---|---|---|
| `olderThan` | `String` | `72h` | Duración Go: una o más `<n><unidad>` con unidades `ns us ms s m h` (`30m`, `72h`, `1h30m`). Otra cosa → error |
| `danglingImages` | `Boolean` | `true` | Borrar también imágenes colgantes (sin etiqueta). Debe ser booleano real |
| `path` | `String` | `/` | Ruta cuyo espacio libre se escribe en el log antes y después |

Cualquier otra clave → `cleanDocker: parámetro desconocido: X`.

## Qué hace

1. Valida parámetros (esto **sí** falla el build, para que una errata no deje de limpiar sin avisar).
2. Si no hay `env.NODE_NAME`: `AVISO: cleanDocker se ha llamado fuera de un nodo…` y termina.
3. Lee el espacio libre (`checkDiskSpace.freeGb(path)`).
4. `docker builder prune -f --filter until=<olderThan>`: caché de build sin usar desde hace más de
   `olderThan`.
5. Si `danglingImages`: `docker image prune -f` (sin `-a`: solo imágenes colgantes).
6. Log `Limpieza de Docker en srv01: 12 GB libres antes, 30 GB después` (`?` si no se pudo leer).

## Garantías

- **Nunca** toca volúmenes, contenedores ni imágenes con etiqueta: nada de `-a`, `system prune` ni
  `volume prune` (en srv01 hay servicios en producción sobre el mismo Docker).
- **No falla el build** si un comando de Docker falla: `AVISO: <paso>: ha fallado con código N. Sigo.`
  Un abort del build (`InterruptedException`) sí se propaga.
- La caché del build recién terminado no se borra hasta pasado `olderThan`.

## Relacionados

[checkDiskSpace](checkDiskSpace.md), [buildMultiArchImage](buildMultiArchImage.md).

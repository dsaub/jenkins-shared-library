// Libera espacio de Docker en el nodo actual: borra la caché de build que lleva más de
// olderThan sin usarse y, si danglingImages, las imágenes colgantes (las que no tienen etiqueta).
// Nunca toca volúmenes, contenedores ni imágenes con etiqueta, y nunca hace fallar el build
// por un comando que falle. Pensada para post { always { cleanDocker() } }.
def call(Map config = [:]) {
    // Un parámetro mal escrito sí falla: si no, la limpieza dejaría de hacerse sin que nadie lo viera.
    List unknown = unknownKeys(config, ['olderThan', 'danglingImages', 'path'])
    if (!unknown.isEmpty()) {
        error("cleanDocker: parámetro desconocido: ${unknown.join(', ')}")
    }
    String olderThan = config.olderThan != null ? String.valueOf(config.olderThan) : '72h'
    if (!isDuration(olderThan)) {
        error("cleanDocker: olderThan debe ser una duración como 72h o 30m, no '${olderThan}'")
    }
    def danglingImages = config.danglingImages != null ? config.danglingImages : true
    if (!(danglingImages instanceof Boolean)) {
        error("cleanDocker: danglingImages debe ser true o false, no '${danglingImages}'")
    }
    String path = config.path != null ? String.valueOf(config.path) : '/'
    if (!env.NODE_NAME) {
        echo 'AVISO: cleanDocker se ha llamado fuera de un nodo, así que no hay Docker que limpiar. Sigo.'
        return
    }

    String where = env.NODE_NAME
    Integer before = freeGbOrNull(path)
    // olderThan ya está validado: solo lleva cifras y unidades, no hace falta entrecomillarlo.
    runOrWarn("docker builder prune -f --filter until=${olderThan}", 'Borrar caché de build')
    if (danglingImages) {
        // Sin -a: solo imágenes colgantes, nunca las que tienen etiqueta.
        runOrWarn('docker image prune -f', 'Borrar imágenes colgantes')
    }
    Integer after = freeGbOrNull(path)
    echo "Limpieza de Docker en ${where}: ${gb(before)} libres antes, ${gb(after)} después"
}

// Ejecuta un comando de limpieza: si falla, lo deja en el log y sigue.
private void runOrWarn(String script, String label) {
    try {
        int status = sh(script: script, returnStatus: true, label: label)
        if (status != 0) {
            echo "AVISO: ${label}: ha fallado con código ${status}. Sigo."
        }
    } catch (InterruptedException e) {
        throw e // el build se está abortando: eso no se oculta
    } catch (Exception e) {
        echo "AVISO: ${label}: no se ha podido ejecutar (${e.message}). Sigo."
    }
}

private Integer freeGbOrNull(String path) {
    try {
        return checkDiskSpace.freeGb(path)
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        return null
    }
}

@NonCPS
private String gb(Integer value) {
    return value == null ? '?' : value + ' GB'
}

@NonCPS
private boolean isDuration(String value) {
    return value ==~ /([0-9]+(ns|us|ms|s|m|h))+/
}

@NonCPS
private List unknownKeys(Map config, List known) {
    return config.keySet().findAll { !known.contains(it) }.collect { String.valueOf(it) }
}

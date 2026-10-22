// Comprueba el espacio libre en el nodo actual y falla si queda menos de minFreeGb.
// Si no puede leerlo, solo avisa. Devuelve los GB libres (o null si no se han podido leer).
def call(Map config = [:]) {
    List unknown = unknownKeys(config, ['minFreeGb', 'path'])
    if (!unknown.isEmpty()) {
        error("checkDiskSpace: parámetro desconocido: ${unknown.join(', ')}")
    }
    Integer minFreeGb = wholeNumber(config.minFreeGb, 10)
    if (minFreeGb == null) {
        error("checkDiskSpace: minFreeGb debe ser un número entero de GB, no '${config.minFreeGb}'")
    }
    String path = config.path != null ? String.valueOf(config.path) : '/'
    if (path.trim().isEmpty()) {
        error('checkDiskSpace: path no puede estar vacío')
    }

    String where = (env.NODE_NAME ?: 'este nodo') + (path == '/' ? '' : ':' + path)
    String output = dfAvail(path)
    Integer freeGb = parseGb(output)
    if (freeGb == null) {
        echo "AVISO: no he podido leer el espacio libre en ${where} (${output}). Sigo sin comprobarlo."
        return null
    }
    if (freeGb < minFreeGb) {
        error("Solo quedan ${freeGb} GB libres en ${where} (mínimo: ${minFreeGb} GB)")
    }
    echo "Espacio libre en ${where}: ${freeGb} GB (mínimo: ${minFreeGb} GB)"
    return freeGb
}

// GB libres en la ruta del nodo actual, o null si no se pueden leer. La usa cleanDocker.
Integer freeGb(String path = '/') {
    return parseGb(dfAvail(path))
}

private String dfAvail(String path) {
    // La ruta va por entorno, no dentro del script. Con 2>&1 | tail, si df falla
    // su error llega al aviso en vez de hacer fallar el sh.
    return withEnv(["MLIB_DF_PATH=${path}"]) {
        sh(script: 'df --output=avail -BG -- "$MLIB_DF_PATH" 2>&1 | tail -n 1',
           returnStdout: true, label: "Espacio libre en ${path}").trim()
    }
}

@NonCPS
private Integer parseGb(String output) {
    return output ==~ /[0-9]{1,9}G/ ? Integer.valueOf(output.substring(0, output.length() - 1)) : null
}

@NonCPS
private Integer wholeNumber(value, int defaultValue) {
    if (value == null) {
        return defaultValue
    }
    String text = String.valueOf(value).trim()
    return text ==~ /[0-9]{1,6}/ ? Integer.valueOf(text) : null
}

@NonCPS
private List unknownKeys(Map config, List known) {
    return config.keySet().findAll { !known.contains(it) }.collect { String.valueOf(it) }
}

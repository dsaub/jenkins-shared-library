import groovy.transform.Field

@Field final String SANDBOX_PATTERN = '[A-Za-z0-9][A-Za-z0-9._-]{0,127}'
@Field final String SNAPSHOT_PATTERN = '[A-Za-z0-9][A-Za-z0-9._:/@-]{0,255}'
@Field final String ENV_NAME_PATTERN = '[A-Z_][A-Z0-9_]*'
@Field final String CLI_URL = 'https://github.com/daytonaio/daytona/releases/latest/download/daytona-linux-'

// Crea un sandbox x86_64 de Daytona con Docker listo para construir y devuelve su ID. Sirve para
// construir linux/amd64 sin emulación cuando los agentes son ARM: el ID se pasa a buildMultiArchImage
// y a dockerPushGhcr con mode 'sandboxing'. Si el nodo no tiene la CLI de Daytona, la descarga.
def call(Map config = [:]) {
    List unknown = unknownKeys(config, ['snapshot', 'name', 'credentialsId', 'target', 'cpu', 'memory', 'disk'])
    if (!unknown.isEmpty()) {
        error("prepareDaytona: parámetro desconocido: ${unknown.join(', ')}")
    }
    List problems = []
    String snapshot = textOf(config.snapshot) ?: 'mlib-dind'
    if (!(snapshot ==~ SNAPSHOT_PATTERN)) {
        problems.add("snapshot no válido '${snapshot}'".toString())
    }
    String name = textOf(config.name) ?: defaultName(env.JOB_BASE_NAME, env.BUILD_NUMBER)
    if (!(name ==~ SANDBOX_PATTERN)) {
        problems.add("nombre no válido '${name}' (letras, cifras, '.', '_' y '-')".toString())
    }
    String credentialsId = textOf(config.credentialsId) ?: 'daytona-token'
    String target = textOf(config.target)
    if (target != null && !(target ==~ /[a-z]+/)) {
        problems.add("target no válido '${target}' (por ejemplo eu o us)".toString())
    }
    List createArgs = ['create', '--snapshot', snapshot, '--name', name]
    if (target != null) {
        createArgs.addAll(['--target', target])
    }
    ['cpu', 'memory', 'disk'].each { key ->
        if (config[key] != null) {
            Integer value = wholeNumber(config[key])
            if (value == null || value < 1) {
                problems.add("'${key}' debe ser un número entero, 1 o más".toString())
            } else {
                createArgs.addAll(['--' + key, String.valueOf(value)])
            }
        }
    }
    if (!problems.isEmpty()) {
        error('prepareDaytona: ' + problems.join('; '))
    }
    requireNode('prepareDaytona')

    // La salida de create va al log (stderr); stdout solo lleva el ID que se lee después con info.
    String id = daytonaSh(credentialsId, 'Crear sandbox de Daytona', [
        'mlib_daytona ' + shellJoin(createArgs) + ' >&2',
        'mlib_daytona info ' + shellQuote(name) + ' -f json' +
            " | grep -o '\"id\"[[:space:]]*:[[:space:]]*\"[^\"]*\"' | head -n 1 | sed 's/.*\"\\([^\"]*\\)\"\$/\\1/'"
    ].join('\n'), true).trim()
    if (!(id ==~ SANDBOX_PATTERN)) {
        echo "AVISO: no he podido leer el ID del sandbox '${name}' (${id}); uso su nombre, que la CLI también acepta."
        id = name
    }
    echo "Sandbox de Daytona: ${id} (${name}, snapshot ${snapshot})"

    try {
        exec(id, 'Preparar Docker en el sandbox', setupScript(), [], credentialsId)
    } catch (Exception e) {
        echo "El sandbox ${id} no está listo; lo borro."
        delete(id, credentialsId)
        throw e
    }
    return id
}

// Borra el sandbox. No hace fallar el build: es para post { always { … } }.
void delete(String sandboxId, String credentialsId = 'daytona-token') {
    if (!(String.valueOf(sandboxId) ==~ SANDBOX_PATTERN)) {
        error("prepareDaytona.delete: ID de sandbox no válido '${sandboxId}'")
    }
    if (!env.NODE_NAME) {
        echo "AVISO: prepareDaytona.delete se ha llamado fuera de un nodo; no borro el sandbox ${sandboxId}."
        return
    }
    try {
        daytonaSh(credentialsId, "Borrar sandbox ${sandboxId}", 'mlib_daytona delete ' + shellQuote(sandboxId), false)
    } catch (InterruptedException e) {
        throw e
    } catch (Exception e) {
        echo "AVISO: no he podido borrar el sandbox ${sandboxId} (${e.message}). Bórralo a mano con 'daytona delete ${sandboxId}'."
    }
}

// Ejecuta un script de sh en el sandbox. Los valores de las variables de entorno locales que se
// nombran en envNames llegan al script como variables con el mismo nombre: se leen en el sh local,
// nunca se interpolan en Groovy. No se fía del código de salida de daytona exec: el script escribe
// el suyo al final y se comprueba aquí. Falla si el script falla.
void exec(String sandboxId, String label, String script, List envNames = [], String credentialsId = 'daytona-token') {
    if (!(String.valueOf(sandboxId) ==~ SANDBOX_PATTERN)) {
        error("prepareDaytona: ID de sandbox no válido '${sandboxId}'")
    }
    List badNames = envNames.findAll { !(String.valueOf(it) ==~ ENV_NAME_PATTERN) }
    if (!badNames.isEmpty()) {
        error("prepareDaytona: nombre de variable no válido: ${badNames.join(', ')}")
    }
    if (hasLine(script, 'MLIB_SCRIPT_END')) {
        error('prepareDaytona: el script no puede tener una línea MLIB_SCRIPT_END')
    }
    requireNode('prepareDaytona.exec')
    withEnv(["MLIB_SANDBOX_ID=${sandboxId}"]) {
        daytonaSh(credentialsId, label, execCommands(script, envNames), false)
    }
}

// Script local con la CLI de Daytona lista en la función mlib_daytona. La CLI se busca en el PATH y
// si no está se descarga una vez a la caché del usuario del nodo. El login va a un HOME temporal que
// se borra al salir, así no se queda la API key en el nodo ni choca con otros builds.
private def daytonaSh(String credentialsId, String label, String commands, boolean returnStdout) {
    withCredentials([string(credentialsId: credentialsId, variable: 'MLIB_DAYTONA_TOKEN')]) {
        return sh(label: label, returnStdout: returnStdout, script: cliScript(commands))
    }
}

@NonCPS
private String cliScript(String commands) {
    return [
        'set -e',
        'mlib_home="$(mktemp -d)"',
        'mlib_end() { mlib_status=$?; rm -rf "$mlib_home"; exit "$mlib_status"; }',
        'trap mlib_end EXIT',
        "trap 'exit 129' HUP",
        "trap 'exit 130' INT",
        "trap 'exit 143' TERM",
        'mlib_bin="$(command -v daytona || true)"',
        'if [ -z "$mlib_bin" ]; then',
        '  case "$(uname -m)" in',
        '    x86_64|amd64) mlib_arch=amd64 ;;',
        '    aarch64|arm64) mlib_arch=arm64 ;;',
        '    *) echo "prepareDaytona: no hay CLI de Daytona para $(uname -m)" >&2; exit 1 ;;',
        '  esac',
        '  mlib_cache="${XDG_CACHE_HOME:-$HOME/.cache}/mlib"',
        '  mlib_bin="$mlib_cache/daytona-$mlib_arch"',
        '  if [ ! -x "$mlib_bin" ]; then',
        '    echo "Descargo la CLI de Daytona en $mlib_bin" >&2',
        '    mkdir -p "$mlib_cache"',
        "    curl -fsSL '${CLI_URL}'\"\$mlib_arch\" -o \"\$mlib_bin.\$\$\"",
        '    chmod +x "$mlib_bin.$$"',
        '    mv "$mlib_bin.$$" "$mlib_bin"',
        '  fi',
        'fi',
        'mlib_daytona() { HOME="$mlib_home" XDG_CONFIG_HOME="$mlib_home/.config" "$mlib_bin" "$@"; }',
        'mlib_daytona login --api-key "$MLIB_DAYTONA_TOKEN" >/dev/null',
        commands
    ].join('\n')
}

// El script viaja en base64 como una sola palabra (daytona exec junta los argumentos en una línea de
// comando), y delante lleva las variables pedidas, entrecomilladas en el sh local.
@NonCPS
private String execCommands(String script, List envNames) {
    List lines = ['mlib_q() { printf "\'%s\'" "$(printf \'%s\' "$1" | sed "s/\'/\'\\\\\\\\\'\'/g")"; }', '{']
    envNames.each { name -> lines.add("  printf '%s=%s\\n' ${name} \"\$(mlib_q \"\${${name}}\")\"".toString()) }
    lines.addAll([
        "  cat <<'MLIB_SCRIPT_END'",
        '(',
        'set -e',
        script,
        ')',
        'echo "mlib-exit:$?"',
        'MLIB_SCRIPT_END',
        '} | base64 | tr -d \'\\n\' > "$mlib_home/payload"',
        // Se ejecuta desde un fichero y con stdin vacío, para que ningún comando se coma el script.
        'mlib_daytona exec "$MLIB_SANDBOX_ID" --timeout 0 -- sh -c "\'mlib_f=\\$(mktemp) && echo $(cat "$mlib_home/payload") | base64 -d > \\$mlib_f && sh \\$mlib_f < /dev/null; mlib_s=\\$?; rm -f \\$mlib_f; exit \\$mlib_s\'" 2>&1 | tee "$mlib_home/out" || true',
        'if ! grep -q \'^mlib-exit:0\' "$mlib_home/out"; then',
        '  echo "El comando ha fallado en el sandbox $MLIB_SANDBOX_ID" >&2',
        '  exit 1',
        'fi'
    ])
    return lines.join('\n')
}

// En el sandbox: comprueba que es x86_64, arranca dockerd si no está y deja git disponible.
@NonCPS
private String setupScript() {
    return '''\
mlib_arch="$(uname -m)"
if [ "$mlib_arch" != x86_64 ]; then
  echo "El sandbox es $mlib_arch, no x86_64" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  command -v dockerd >/dev/null || { echo "El snapshot no tiene dockerd (usa una imagen docker:*-dind)" >&2; exit 1; }
  nohup dockerd >/tmp/mlib-dockerd.log 2>&1 &
  mlib_i=0
  until docker info >/dev/null 2>&1; do
    mlib_i=$((mlib_i + 1))
    if [ "$mlib_i" -gt 60 ]; then
      tail -n 30 /tmp/mlib-dockerd.log >&2
      echo "dockerd no arranca en el sandbox" >&2
      exit 1
    fi
    sleep 1
  done
fi
docker buildx version
if ! command -v git >/dev/null; then
  if command -v apk >/dev/null; then apk add --no-cache git >/dev/null
  elif command -v apt-get >/dev/null; then apt-get update -qq && apt-get install -y -qq git >/dev/null
  else echo "El sandbox no tiene git" >&2; exit 1
  fi
fi
echo "Docker $(docker version --format '{{.Server.Version}} {{.Server.Arch}}') listo en el sandbox"'''
}

private void requireNode(String step) {
    if (!env.NODE_NAME) {
        error("${step}: tiene que ejecutarse dentro de un nodo (usa la CLI de Daytona desde el agente).")
    }
}

// mlib-<job>-<build>, solo con caracteres válidos.
@NonCPS
private String defaultName(String job, String build) {
    String base = ('mlib-' + (job ?: 'build') + '-' + (build ?: '0')).replaceAll(/[^A-Za-z0-9._-]+/, '-')
    return base.length() > 63 ? base.substring(0, 63) : base
}

@NonCPS
private boolean hasLine(String text, String line) {
    return text.readLines().contains(line)
}

@NonCPS
private String shellJoin(List args) {
    return args.collect { shellQuote(String.valueOf(it)) }.join(' ')
}

@NonCPS
private String shellQuote(String value) {
    return value ==~ /[A-Za-z0-9_.\/:=,@%+-]+/ ? value : "'" + value.replace("'", "'\\''") + "'"
}

@NonCPS
private String textOf(value) {
    String text = value == null ? '' : String.valueOf(value).trim()
    return text.isEmpty() ? null : text
}

@NonCPS
private Integer wholeNumber(value) {
    String text = String.valueOf(value).trim()
    return text ==~ /[0-9]{1,6}/ ? Integer.valueOf(text) : null
}

@NonCPS
private List unknownKeys(Map config, List known) {
    return config.keySet().findAll { !known.contains(it) }.collect { String.valueOf(it) }
}

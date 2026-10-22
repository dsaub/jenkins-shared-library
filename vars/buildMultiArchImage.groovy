import groovy.transform.Field

// Formatos de Docker para nombres de imagen, etiquetas y plataformas. Es una versión simplificada
// de la gramática oficial, suficiente para que no llegue nada raro a la línea de comandos.
@Field final String HOST_PATTERN = '[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?)*(?::[0-9]+)?'
@Field final String PATH_PATTERN = '[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*'
@Field final String TAG_PATTERN = '[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}'
@Field final String PLATFORM_PATTERN = '[a-z0-9]+/[a-z0-9_]+(?:/[a-z0-9]+)?'
@Field final String BANNER = '****************************************************************************'

// Construye una imagen multiarquitectura sin emulación. Cada plataforma se construye y se sube
// en un agente de su arquitectura como <imagen>:<commit>-<arch>; al final se juntan en un
// manifiesto con todas las etiquetas. Devuelve las referencias publicadas (imagen:etiqueta).
def call(Map config = [:]) {
    Map cfg = parseConfig(config)
    if (!cfg.problems.isEmpty()) {
        error('buildMultiArchImage: ' + cfg.problems.join('; '))
    }
    if (env.NODE_NAME) {
        echo "AVISO: buildMultiArchImage se ha llamado dentro del nodo ${env.NODE_NAME}. Pide sus propios " +
             "agentes, así que ese ejecutor se queda ocupado esperando. Mejor llámala con 'agent none'."
    }

    List targets = planTargets(cfg)

    // Un Map de ramas para parallel; cada closure usa su propia variable target.
    Map results = [:]
    Map branches = [failFast: true]
    for (int i = 0; i < targets.size(); i++) {
        Map target = targets[i]
        branches[target.platform] = {
            results[target.platform] = buildTarget(cfg, target)
        }
    }
    parallel(branches)

    List commitTags = distinctCommitTags(results)
    if (commitTags.size() != 1) {
        error("Las plataformas se han construido desde commits distintos (${commitTags.join(', ')}): no creo el manifiesto.")
    }
    List refs = finalRefs(cfg, commitTags[0])
    node(targets[0].label) {
        registrySh(cfg, 'docker buildx imagetools create', manifestCommand(refs, sourceRefs(targets, results)))
    }
    echo "Publicada: ${refs.join(', ')}"
    return refs
}

// Decide en qué agente se construye cada plataforma antes de construir nada. Si falta el de alguna,
// falla; con allowEmulation, la construye emulada en el agente de otra plataforma.
private List planTargets(Map cfg) {
    List labels = distinctLabels(cfg.platforms, cfg.agents)
    Map status = checkAgents(labels, cfg.agentTimeoutMinutes)
    String host = firstAvailable(labels, status)

    List targets = []
    List missing = []
    for (int i = 0; i < cfg.platforms.size(); i++) {
        String platform = cfg.platforms[i]
        String label = cfg.agents[platform]
        boolean available = status[label] == 'ok'
        String reason = available ? null : missingReason(label, status[label], cfg.agentTimeoutMinutes)
        if (!available) {
            missing.add(platform + ': ' + reason)
        }
        targets.add([platform: platform, arch: archOf(platform), suffix: suffixOf(platform),
                     label: available ? label : host, emulated: !available, reason: reason])
    }
    if (!missing.isEmpty()) {
        if (!cfg.allowEmulation) {
            error("Falta agente para ${missing.join('; ')}. Pon en línea un nodo con esa etiqueta o usa allowEmulation: true.")
        }
        if (host == null) {
            error("No hay ningún agente donde construir: ${missing.join('; ')}.")
        }
        for (int i = 0; i < targets.size(); i++) {
            if (targets[i].emulated) {
                emulationWarning(targets[i].platform, "un agente '${host}'", targets[i].reason)
            }
        }
    }
    echo "Plan: ${planSummary(targets)}"
    return targets
}

// Estado de cada etiqueta: 'ok', 'offline' (no hay ningún nodo en línea) o 'timeout'. Sin el plugin
// Pipeline Utility Steps no se puede saber de antemano, así que se pide un nodo de cada etiqueta y
// se espera como mucho timeoutMinutes, en vez de quedarse esperando indefinidamente.
private Map checkAgents(List labels, int timeoutMinutes) {
    Map status = [:]
    Map probes = [:]
    for (int i = 0; i < labels.size(); i++) {
        String label = labels[i]
        List nodes = onlineNodes(label)
        if (nodes != null) {
            status[label] = nodes.isEmpty() ? 'offline' : 'ok'
        } else {
            probes['agente ' + label] = {
                status[label] = probeAgent(label, timeoutMinutes) ? 'ok' : 'timeout'
            }
        }
    }
    if (!probes.isEmpty()) {
        echo "Compruebo los agentes pidiendo un nodo de cada etiqueta (máximo ${timeoutMinutes} min)."
        parallel(probes)
    }
    return status
}

// Nodos en línea con la etiqueta según Pipeline Utility Steps, o null si el plugin no está instalado.
private List onlineNodes(String label) {
    try {
        return nodesByLabel(label: label)
    } catch (NoSuchMethodError ignored) {
        return null
    }
}

// Pide un nodo con la etiqueta y lo suelta enseguida. Devuelve false si no llega en timeoutMinutes.
private boolean probeAgent(String label, int timeoutMinutes) {
    long start = System.currentTimeMillis()
    try {
        timeout(time: timeoutMinutes, unit: 'MINUTES') {
            node(label) {
                echo "Agente para '${label}': ${env.NODE_NAME}"
            }
        }
        return true
    } catch (Exception e) {
        // Un abort del build también llega aquí: solo es el timeout si ya ha pasado el tiempo.
        if (System.currentTimeMillis() - start < timeoutMinutes * 60000L) {
            throw e
        }
        return false
    }
}

// Construye y sube una plataforma en su agente. Al terminar limpia Docker, también si el build falla.
private Map buildTarget(Map cfg, Map target) {
    Map result = [:]
    node(target.label) {
        // Fuera del try: si el nodo está mal etiquetado, la rama falla al momento (aún no hay nada que
        // limpiar) y failFast para las demás antes de que lleguen a construir.
        checkArch(cfg, target)
        try {
            def scmVars = checkout(scm)
            String commitTag = commitTagOf(scmVars)
            String ref = cfg.image + ':' + commitTag + '-' + target.suffix
            checkDiskSpace(minFreeGb: cfg.minFreeGb)
            registrySh(cfg, "docker buildx build ${target.platform}",
                       buildCommand(cfg, target.platform, ref) + '\n' + untagCommand(ref))
            result.commitTag = commitTag
            result.ref = ref
        } finally {
            cleanDocker()
        }
    }
    return result
}

// <commit>: los 12 primeros caracteres del commit que se ha construido; sin commit, el número de build.
private String commitTagOf(scmVars) {
    String commit = scmVars instanceof Map ? scmVars.GIT_COMMIT : null
    if (!commit) {
        commit = env.GIT_COMMIT
    }
    if (!commit) {
        echo "AVISO: no hay commit de git; uso el número de build (${env.BUILD_NUMBER}) en las etiquetas."
        return env.BUILD_NUMBER
    }
    String tag = shortCommit(commit)
    if (!isTag(tag)) {
        error("No puedo usar el commit '${commit}' como etiqueta de Docker.")
    }
    return tag
}

// Si el Docker del nodo no es de la arquitectura de la plataforma, el build sería emulado:
// falla, salvo con allowEmulation.
private void checkArch(Map cfg, Map target) {
    String nodeArch = sh(script: "docker version --format '{{.Server.Arch}}' 2>/dev/null || true",
                         returnStdout: true, label: 'Arquitectura de Docker').trim()
    if (!nodeArch) {
        echo "AVISO: no he podido leer la arquitectura de Docker en ${env.NODE_NAME}; no compruebo si habrá emulación."
    } else if (nodeArch != target.arch) {
        if (!cfg.allowEmulation) {
            error("${env.NODE_NAME} (etiqueta '${target.label}') es ${nodeArch}: ${target.platform} se construiría emulada. " +
                  "Revisa las etiquetas del nodo y 'agents', o usa allowEmulation: true.")
        }
        if (target.emulated) {
            echo "Emulando ${target.platform} en ${env.NODE_NAME} (${nodeArch})."
        } else {
            emulationWarning(target.platform, "${env.NODE_NAME} (${nodeArch})",
                             "el Docker de ${env.NODE_NAME} es ${nodeArch}, no ${target.arch} (etiqueta '${target.label}')")
        }
    }
}

private void emulationWarning(String platform, String where, String reason) {
    echo """${BANNER}
AVISO: ${platform} se va a construir EMULADA con QEMU en ${where}.
Motivo: ${reason}.
Será lento y puede colgarse (sin volcados de memoria: va con --ulimit core=0).
${BANNER}"""
}

// Ejecuta comandos de Docker con sesión en el registro. Usuario y contraseña llegan al sh como
// variables de entorno: nunca se interpolan en Groovy, y Jenkins los enmascara en el log.
private void registrySh(Map cfg, String label, String commands) {
    withCredentials([usernamePassword(credentialsId: cfg.credentialsId,
                                      usernameVariable: 'MLIB_REGISTRY_USER',
                                      passwordVariable: 'MLIB_REGISTRY_PASSWORD')]) {
        sh(label: label, script: registryScript(cfg.registry, commands))
    }
}

// El login se guarda en un DOCKER_CONFIG temporal que se borra al salir, haya ido bien o mal, para no
// pisar el login de otros builds del mismo nodo (ni que ellos pisen este). Se copia la configuración
// del nodo (proxies, otros registros) y se enlazan los plugins de la CLI y los builders de buildx.
@NonCPS
private String registryScript(String registry, String commands) {
    String server = registry == 'docker.io' ? '' : ' ' + shellQuote(registry)
    return [
        'set -e',
        'mlib_config="${DOCKER_CONFIG:-$HOME/.docker}"',
        'DOCKER_CONFIG="$(mktemp -d)"',
        'export DOCKER_CONFIG',
        'mlib_end() {',
        '  mlib_status=$?',
        '  docker logout' + server + ' >/dev/null 2>&1 || true',
        '  rm -rf "$DOCKER_CONFIG"',
        '  exit "$mlib_status"',
        '}',
        'trap mlib_end EXIT',
        "trap 'exit 129' HUP",
        "trap 'exit 130' INT",
        "trap 'exit 143' TERM",
        'if [ -f "$mlib_config/config.json" ]; then cp "$mlib_config/config.json" "$DOCKER_CONFIG/"; fi',
        'for mlib_dir in cli-plugins buildx; do',
        '  if [ -e "$mlib_config/$mlib_dir" ]; then ln -s "$mlib_config/$mlib_dir" "$DOCKER_CONFIG/$mlib_dir"; fi',
        'done',
        'printf \'%s\' "$MLIB_REGISTRY_PASSWORD" | docker login' + server + ' -u "$MLIB_REGISTRY_USER" --password-stdin',
        commands
    ].join('\n')
}

@NonCPS
private String buildCommand(Map cfg, String platform, String ref) {
    List args = ['docker', 'buildx', 'build', '--platform', platform,
                 '--ulimit', 'core=0', '--provenance=false', '--push']
    if (cfg.dockerfile != null) {
        args.addAll(['-f', cfg.dockerfile])
    }
    args.addAll(['-t', ref])
    cfg.buildArgs.each { name, value -> args.addAll(['--build-arg', name + '=' + value]) }
    args.add(cfg.context)
    return shellJoin(args)
}

// Con el driver docker, --push también deja la imagen etiquetada en el nodo. Ya subida, esa etiqueta
// sobra: si se quedara, cada commit dejaría una imagen que cleanDocker no borra. Va sin -f: si algún
// contenedor la usa, Docker no la borra.
@NonCPS
private String untagCommand(String ref) {
    return 'docker image rm ' + shellQuote(ref) + ' >/dev/null 2>&1 || true'
}

@NonCPS
private String manifestCommand(List refs, List sources) {
    List args = ['docker', 'buildx', 'imagetools', 'create']
    refs.each { args.addAll(['-t', it]) }
    args.addAll(sources)
    return shellJoin(args)
}

@NonCPS
private String shellJoin(List args) {
    return args.collect { shellQuote(String.valueOf(it)) }.join(' ')
}

// Entrecomilla un argumento para sh si tiene algún carácter que no sea claramente inofensivo.
@NonCPS
private String shellQuote(String value) {
    return value ==~ /[A-Za-z0-9_.\/:=,@%+-]+/ ? value : "'" + value.replace("'", "'\\''") + "'"
}

// Valida los parámetros y aplica los valores por defecto. Devuelve la configuración junto con la
// lista de problemas encontrados, vacía si todo está bien.
@NonCPS
private Map parseConfig(Map config) {
    List problems = []
    List known = ['image', 'tags', 'platforms', 'agents', 'credentialsId', 'registry', 'dockerfile',
                  'context', 'buildArgs', 'allowEmulation', 'minFreeGb', 'agentTimeoutMinutes']
    config.keySet().findAll { !known.contains(it) }.each { problems.add("parámetro desconocido '${it}'".toString()) }

    String image = textOf(config.image)
    if (image == null) {
        problems.add("falta 'image'")
    } else if (image.length() > 255 || !(image ==~ "(?:${HOST_PATTERN}/)?${PATH_PATTERN}(?:/${PATH_PATTERN})*")) {
        problems.add("imagen no válida '${image}' (en minúsculas y sin etiqueta ni digest)".toString())
    }

    List tags = listOf(config.tags, ['latest'])
    tags.findAll { !isTag(it) }.each { problems.add("etiqueta no válida '${it}'".toString()) }

    List platforms = listOf(config.platforms, ['linux/arm64', 'linux/amd64'])
    if (platforms.isEmpty()) {
        problems.add("'platforms' está vacío")
    }
    platforms.findAll { !(it ==~ PLATFORM_PATTERN) }.each { problems.add("plataforma no válida '${it}'".toString()) }
    if (distinct(platforms.collect { suffixOf(it) }).size() != platforms.size()) {
        problems.add("hay plataformas repetidas en 'platforms'")
    }

    Map agents = ['linux/arm64': 'arm64', 'linux/amd64': 'amd64']
    if (config.agents instanceof Map) {
        config.agents.each { platform, label -> agents[String.valueOf(platform)] = textOf(label) }
    } else if (config.agents != null) {
        problems.add("'agents' debe ser un Map de plataforma a etiqueta")
    }
    platforms.findAll { agents[it] == null }.each { problems.add("falta la etiqueta de agente de '${it}' en 'agents'".toString()) }

    String credentialsId = textOf(config.credentialsId)
    if (credentialsId == null) {
        problems.add("falta 'credentialsId'")
    }

    String registry = textOf(config.registry) ?: registryOf(image ?: '')
    if (!(registry ==~ HOST_PATTERN)) {
        problems.add("registro no válido '${registry}'".toString())
    }

    // Sin dockerfile, docker usa el Dockerfile del contexto, así que no se pasa -f.
    String dockerfile = textOf(config.dockerfile)
    String context = textOf(config.context) ?: '.'
    if (dockerfile?.startsWith('-') || context.startsWith('-')) {
        problems.add("'dockerfile' y 'context' no pueden empezar por '-'")
    }

    Map buildArgs = [:]
    if (config.buildArgs instanceof Map) {
        config.buildArgs.each { key, value ->
            String name = String.valueOf(key)
            if (!(name ==~ /[A-Za-z_][A-Za-z0-9_]*/)) {
                problems.add("nombre de buildArg no válido '${name}'".toString())
            } else if (value == null) {
                problems.add("el buildArg '${name}' no tiene valor".toString())
            } else {
                buildArgs[name] = String.valueOf(value)
            }
        }
    } else if (config.buildArgs != null) {
        problems.add("'buildArgs' debe ser un Map")
    }

    def allowEmulation = config.allowEmulation != null ? config.allowEmulation : false
    if (!(allowEmulation instanceof Boolean)) {
        problems.add("'allowEmulation' debe ser true o false")
    }
    Integer minFreeGb = wholeNumber(config.minFreeGb, 10)
    if (minFreeGb == null) {
        problems.add("'minFreeGb' debe ser un número entero de GB")
    }
    Integer agentTimeoutMinutes = wholeNumber(config.agentTimeoutMinutes, 10)
    if (agentTimeoutMinutes == null || agentTimeoutMinutes < 1) {
        problems.add("'agentTimeoutMinutes' debe ser un número entero de minutos, 1 o más")
    }

    return [problems: problems, image: image, tags: distinct(tags), platforms: platforms,
            agents: agents, credentialsId: credentialsId, registry: registry, dockerfile: dockerfile,
            context: context, buildArgs: buildArgs, allowEmulation: allowEmulation, minFreeGb: minFreeGb,
            agentTimeoutMinutes: agentTimeoutMinutes]
}

// El registro es el primer componente del nombre si parece un host (lleva '.' o ':', o es localhost).
@NonCPS
private String registryOf(String image) {
    int slash = image.indexOf('/')
    String first = slash > 0 ? image.substring(0, slash) : ''
    return (first.contains('.') || first.contains(':') || first == 'localhost') ? first : 'docker.io'
}

@NonCPS
private boolean isTag(String tag) {
    return tag ==~ TAG_PATTERN
}

@NonCPS
private String archOf(String platform) {
    return platform.split('/')[1]
}

// linux/arm64 → arm64, linux/arm/v7 → arm-v7
@NonCPS
private String suffixOf(String platform) {
    return platform.substring(platform.indexOf('/') + 1).replace('/', '-')
}

@NonCPS
private String shortCommit(String commit) {
    return commit.length() > 12 ? commit.substring(0, 12) : commit
}

@NonCPS
private String missingReason(String label, String status, int timeoutMinutes) {
    if (status == 'offline') {
        return "no hay ningún nodo en línea con la etiqueta '${label}'"
    }
    return "no he conseguido un nodo con la etiqueta '${label}' en ${timeoutMinutes} min (no hay ninguno en línea o están todos ocupados)"
}

@NonCPS
private List distinctLabels(List platforms, Map agents) {
    return distinct(platforms.collect { agents[it] })
}

@NonCPS
private String firstAvailable(List labels, Map status) {
    return labels.find { status[it] == 'ok' }
}

@NonCPS
private String planSummary(List targets) {
    return targets.collect { it.platform + " en '" + it.label + "'" + (it.emulated ? ' (emulada)' : '') }.join(', ')
}

@NonCPS
private List distinctCommitTags(Map results) {
    return distinct(results.values().collect { it.commitTag })
}

@NonCPS
private List finalRefs(Map cfg, String commitTag) {
    List tags = distinct(cfg.tags)
    if (!tags.contains(commitTag)) {
        tags.add(commitTag)
    }
    return tags.collect { cfg.image + ':' + it }
}

@NonCPS
private List sourceRefs(List targets, Map results) {
    return targets.collect { results[it.platform].ref }
}

@NonCPS
private List listOf(value, List defaultValue) {
    if (value == null) {
        return defaultValue.collect { it }
    }
    return value instanceof Collection ? value.collect { String.valueOf(it) } : [String.valueOf(value)]
}

// Sin repetidos y en el mismo orden. El sandbox no deja crear LinkedHashSet ni HashSet.
@NonCPS
private List distinct(List values) {
    List result = []
    values.each {
        if (!result.contains(it)) {
            result.add(it)
        }
    }
    return result
}

@NonCPS
private String textOf(value) {
    String text = value == null ? '' : String.valueOf(value).trim()
    return text.isEmpty() ? null : text
}

@NonCPS
private Integer wholeNumber(value, int defaultValue) {
    if (value == null) {
        return defaultValue
    }
    String text = String.valueOf(value).trim()
    return text ==~ /[0-9]{1,6}/ ? Integer.valueOf(text) : null
}

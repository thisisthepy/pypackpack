package org.thisisthepy.python.multiplatform.packpack.util

internal val knownBooleanFlags =
    setOf(
        "dev",
        "editable",
        "no-sync",
        "no-cache",
        "quiet",
        "verbose",
        "upgrade",
        "reinstall",
        "refresh",
        "frozen",
        "locked",
        "preview",
        "raw-sources",
    )

internal data class ParsedCommandArgs(
    val dependencies: List<String> = emptyList(),
    val targets: List<String> = emptyList(),
    val extraArgs: Map<String, String> = emptyMap(),
)

internal fun addFlagToExtraArgs(
    args: Array<String>,
    index: Int,
    extraArgs: MutableMap<String, String>,
    stopValueTokens: Set<String> = emptySet(),
): Int {
    val flagName = args[index].substring(2)
    if (knownBooleanFlags.contains(flagName)) {
        extraArgs[flagName] = ""
        return index + 1
    }

    val next = args.getOrNull(index + 1)
    return if (next != null && !next.startsWith("--") && next !in stopValueTokens) {
        extraArgs[flagName] = next
        index + 2
    } else {
        extraArgs[flagName] = ""
        index + 1
    }
}

internal fun parseDependenciesAndExtraArgs(
    args: Array<String>,
    startIndex: Int,
): ParsedCommandArgs {
    val dependencies = mutableListOf<String>()
    val extraArgs = mutableMapOf<String, String>()

    var i = startIndex
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            i = addFlagToExtraArgs(args, i, extraArgs)
        } else {
            dependencies.add(arg)
            i++
        }
    }

    return ParsedCommandArgs(dependencies = dependencies, extraArgs = extraArgs)
}

internal fun parseExtraArgsOnly(
    args: Array<String>,
    startIndex: Int,
    onUnexpected: (String) -> Unit,
): Map<String, String> {
    val extraArgs = mutableMapOf<String, String>()

    var i = startIndex
    while (i < args.size) {
        val arg = args[i]
        if (arg.startsWith("--")) {
            i = addFlagToExtraArgs(args, i, extraArgs)
        } else {
            onUnexpected(arg)
            i++
        }
    }

    return extraArgs
}

internal fun parseTargetsExtraArgsAndMaybeDependencies(
    args: Array<String>,
    startIndex: Int,
    collectDependencies: Boolean,
    onUnexpectedNonTarget: (String) -> Unit,
): ParsedCommandArgs {
    val dependencies = mutableListOf<String>()
    val targets = mutableListOf<String>()
    val extraArgs = mutableMapOf<String, String>()

    var i = startIndex
    var inTarget = false

    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--target" -> {
                inTarget = true
                i++
            }

            arg.startsWith("--") -> {
                inTarget = false
                i = addFlagToExtraArgs(args, i, extraArgs, stopValueTokens = setOf("--target"))
            }

            else -> {
                if (inTarget) {
                    targets.add(arg)
                } else if (collectDependencies) {
                    dependencies.add(arg)
                } else {
                    onUnexpectedNonTarget(arg)
                }
                i++
            }
        }
    }

    return ParsedCommandArgs(dependencies = dependencies, targets = targets, extraArgs = extraArgs)
}

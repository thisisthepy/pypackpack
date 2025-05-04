package org.thisisthepy.python.multiplatform.packpack

import org.thisisthepy.python.multiplatform.packpack.build.CrossPlatformBuilder
import org.thisisthepy.python.multiplatform.packpack.runtime.CrossEnv

object PypackpackCli {
    private val builder = CrossPlatformBuilder()
    private val env = CrossEnv()

    fun parseArgs(args: Array<String>) {
        if (args.isEmpty()) {
            showHelp()
            return
        }

        when (args[0].lowercase()) {
            "init" -> {
                val projectArgIndex = if (args.size > 1) 1 else -1
                val pathArgIndex = if (args.size > 2) 2 else -1

                val isFirstArgPath = args.getOrNull(1)?.let { arg ->
                    arg.startsWith("/") || arg.startsWith("./") || arg.startsWith("../") || arg.contains(":")
                } == true

                val projectName: String? = if (projectArgIndex >= 0 && !isFirstArgPath) args[projectArgIndex] else null
                val path: String? = if (isFirstArgPath && projectArgIndex >= 0) args[projectArgIndex]
                else if (pathArgIndex >= 0) args[pathArgIndex]
                else null

                env.init(projectName, path)
            }
            "add" -> {
                if (args.size < 2) {
                    println("Error: Module name is required")
                    println("Usage: ppp add <module-name> [options]")
                    return
                }
                val moduleName = args[1]
                val path = if (args.size > 2) args[2] else null
                val options = parseOptions(args.drop(2).toTypedArray())
                env.addModule(moduleName, path)
            }
            "remove" -> {
                if (args.size < 2) {
                    println("Error: Module name is required")
                    println("Usage: ppp remove <module-name>")
                    return
                }
                val moduleName = args[1]
                val path = if (args.size > 2) args[2] else null
                env.removeModule(moduleName, path)
            }
            "help", "--help", "-h" -> showHelp()
            else -> {
                println("Unknown command: ${args[0]}")
                showHelp()
            }
        }
    }

    private fun parseOptions(args: Array<String>): Map<String, String> {
        val options = mutableMapOf<String, String>()
        var i = 0

        while (i < args.size) {
            if (args[i].startsWith("--")) {
                val key = args[i].substring(2)
                val value = if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                    i++
                    args[i]
                } else {
                    "true" // 값이 없는 플래그 옵션의 경우
                }
                options[key] = value
            }
            i++
        }

        return options
    }

    private fun showHelp() {
        println("""
            PyPackPack (PPP) - A multiplatform solution to distribute python project.

            Usage: ppp <command> [options]

            Commands:
                init <project-name>       Initialize python multiplatform project
                add <module-name>         Add a sub-module to the project
                remove <module-name>      Remove a sub-module from the project
                help                      Show this help message
                
            Examples:
                ppp init my-project       Initialize a new project named 'my-project'
                ppp add numpy             Add numpy module to the project
        """.trimIndent())  // TODO: 자동으로 실행 exe 이름 변경해서 출력하기 (ppp로 했으면 ppp, pypackpack으로 했으면 pypackpack)
        // TODO: Optional 태그 제대로 표기
    }
}

fun main(args: Array<String>) {
    val testArgs = arrayOf("add", "first", "./ppp_test")
    PypackpackCli.parseArgs(args.ifEmpty { testArgs })
//    PypackpackCli.parseArgs(args)
}

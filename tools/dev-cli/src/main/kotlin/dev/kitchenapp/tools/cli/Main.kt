package dev.kitchenapp.tools.cli

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "doctor" -> println("Kitchen developer CLI: bootstrap OK")
        null -> println("Kitchen developer CLI. Next command to implement: inspect-paprika")
        else -> println("Unknown command: ${args.first()}. Next command to implement: inspect-paprika")
    }
}

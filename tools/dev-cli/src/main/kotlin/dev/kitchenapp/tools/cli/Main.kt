package dev.kitchenapp.tools.cli

import dev.kitchenapp.data.importers.archive.GzipOutcome
import dev.kitchenapp.data.importers.archive.ImportSource
import dev.kitchenapp.data.importers.archive.PaprikaJsonInspector
import dev.kitchenapp.data.importers.archive.ZipFileArchiveReader
import dev.kitchenapp.data.importers.archive.aggregateJsonKeys
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "doctor" -> println("Kitchen developer CLI: bootstrap OK")
        "inspect-paprika" -> inspectPaprika(args.drop(1))
        null -> usage()
        else -> {
            println("Unknown command: ${args.first()}")
            usage()
        }
    }
}

private fun usage() {
    println("Commands:")
    println("  doctor")
    println("  inspect-paprika <path/to/export.paprikarecipes>")
}

private fun inspectPaprika(args: List<String>) {
    val path = args.firstOrNull()
    if (path == null) {
        println("Usage: inspect-paprika <path/to/export.paprikarecipes>")
        exitProcess(2)
    }
    val source = ImportSource(path)
    val reader = ZipFileArchiveReader()
    val report = try {
        reader.inspect(source)
    } catch (e: Exception) {
        println("error: cannot open archive: ${e::class.simpleName}")
        exitProcess(1)
    }
    println("archive: ${report.source.fileName}")
    println("file count: ${report.fileCount}")
    println("gzip success: ${report.gzipSuccessCount}")
    println("gzip failure: ${report.gzipFailureCount}")
    report.entries.forEach { entry ->
        val line = buildString {
            append(entry.name)
            append("\tcompressed=").append(entry.compressedSizeBytes)
            append("\tuncompressed=").append(entry.uncompressedSizeBytes)
            append("\tgzip=").append(entry.gzipOutcome)
            if (entry.gzipOutcome == GzipOutcome.FAILED) {
                append("\treason=").append(entry.gzipFailureReason)
            }
        }
        println(line)
    }

    val inspector = PaprikaJsonInspector()
    val summaries = report.entries
        .filter { it.gzipOutcome == GzipOutcome.SUCCESS }
        .mapNotNull { entry ->
            reader.readEntryContent(source, entry.name)?.let { inspector.summarize(entry.name, it) }
        }
    println("json entries: ${summaries.size}")
    summaries.filter { it.parseError != null }.forEach {
        println("json parse error: ${it.entryName}\treason=${it.parseError}")
    }
    summaries.aggregateJsonKeys().forEach { key ->
        val types = key.typeCounts.entries.joinToString("|") { "${it.key}×${it.value}" }
        val examples = key.examples.filterNotNull().joinToString(" | ").ifEmpty { "-" }
        println(
            "key: ${key.key}\tpresent=${key.presentCount}/${key.entryCount}\t" +
                "null=${key.nullCount}\tmissing=${key.missingCount}\t" +
                "types=$types\texamples=$examples",
        )
    }
}

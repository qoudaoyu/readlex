package com.readvocab.core.importer

object BookImporter {
    /** Detects the format from the file name / magic bytes and parses it. */
    fun parse(bytes: ByteArray, fileName: String, mimeType: String? = null): ParsedBook {
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".epub") || mimeType == "application/epub+zip" || isZip -> EpubImporter.parse(bytes, fileName)
            lower.endsWith(".txt") || mimeType?.startsWith("text/") == true || !isZip -> TxtImporter.parse(bytes, fileName)
            else -> throw ImportException("暂不支持这种文件格式，请导入 TXT 或 EPUB")
        }
    }
}

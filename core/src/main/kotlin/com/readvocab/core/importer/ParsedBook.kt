package com.readvocab.core.importer

data class ParsedChapter(val title: String, val text: String)

data class ParsedBook(val title: String, val author: String, val chapters: List<ParsedChapter>)

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

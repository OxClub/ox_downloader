package com.oxclub.universaldownloader.ui.navigation

object Routes {
    const val HOME = "home"
    const val DOWNLOADS = "downloads"
    const val DOWNLOADS_PATTERN = "downloads?category={category}"
    const val CATEGORIES = "categories"
    const val BROWSER = "browser"
    const val SETTINGS = "settings"
    const val ANALYSIS = "analysis"
    const val BATCH = "batch"
    const val STORAGE = "storage"
    const val ABOUT = "about"
    const val HELP = "help"
    const val FAQ = "faq"
    const val PRIVACY = "privacy"
    const val LICENSES = "licenses"
    const val DETAILS_PATTERN = "details/{id}"

    fun details(id: Long) = "details/$id"
    fun downloads(category: String? = null) = if (category == null) DOWNLOADS else "downloads?category=$category"

    val topLevel = setOf(HOME, DOWNLOADS, DOWNLOADS_PATTERN, CATEGORIES, BROWSER, SETTINGS)
}

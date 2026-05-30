package io.legado.app.lib.epublib.domain

@Suppress("unused")
enum class ManifestItemProperties(override val propertyName: String) : ManifestProperties {
    COVER_IMAGE("cover-image"),
    MATHML("mathml"),
    NAV("nav"),
    REMOTE_RESOURCES("remote-resources"),
    SCRIPTED("scripted"),
    SVG("svg"),
    SWITCH("switch");
}

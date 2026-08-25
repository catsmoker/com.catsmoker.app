package com.catsmoker.app.shared.data.model

/**
 * Why a metric holds the value it holds.
 *
 * A reading that could not be taken carries one of these instead of a number, so the UI can say
 * what happened rather than print a 0 the device never reported.
 *
 * @param label short text the UI can show in place of a value.
 */
enum class MetricReadStatus(val label: String) {
    /** No reading has been taken yet. */
    Loading("reading…"),

    /** A real value was read. */
    Ok("ok"),

    /** The source answered, but with nothing in it. */
    EmptyOutput("no data"),

    /** The source answered with something that could not be read as a value. */
    ParseFailed("unreadable"),

    /** The source exists, but reaching it needs root or Shizuku. */
    PrivilegeDenied("needs root/Shizuku"),

    /** This device or Android version does not expose the value at all. */
    Unsupported("unsupported");

    val isOk: Boolean get() = this == Ok
}

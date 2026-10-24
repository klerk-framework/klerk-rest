package dev.klerkframework.rest

/** Where the API is in its lifecycle. Shown as `apiStatus` by `GET api-info`. */
public enum class ApiStatus(
    internal val value: String,
) {
    Alpha("alpha"),
    Beta("beta"),
    Active("active"),
    Deprecated("deprecated"),
    Retired("retired"),
    Decommissioned("decommissioned"),
}

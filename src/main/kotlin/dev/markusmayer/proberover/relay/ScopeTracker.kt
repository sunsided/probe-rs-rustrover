package dev.markusmayer.proberover.relay

import com.google.gson.JsonObject

/**
 * Two things about probe-rs's scopes:
 * - RustRover shows the first scope with hint `registers` as its built-in Registers node. probe-rs gives that hint to
 *   both Peripherals (from the SVD file, listed first) and the CPU Registers, so the relay renames the Peripherals
 *   hint and RustRover shows the CPU registers.
 * - RustRover only asks for the locals, so the plugin's own probe-rs tab shows the other scopes. It needs the stack
 *   frame RustRover is showing: the one of the IDE's last `scopes` request, until the target runs again.
 */
class ScopeTracker {
    /** The frame whose scopes RustRover last asked for, or null while the target runs. */
    @Volatile var frameId: Int? = null
        private set

    /** A `scopes` request from the IDE. */
    fun onScopesRequest(request: JsonObject) {
        request.getAsJsonObject("arguments")?.get("frameId")?.asInt?.let { frameId = it }
    }

    /** A `scopes` response body, from probe-rs or the cache. Returns true if it was changed in place. */
    fun onScopes(body: JsonObject): Boolean {
        val scopes = body.getAsJsonArray("scopes")?.map { it.asJsonObject } ?: return false
        fun named(name: String) = scopes.firstOrNull { it.get("name")?.asString == name }
        val peripherals = named("Peripherals") ?: return false
        if (peripherals.get("presentationHint")?.asString != "registers" || named("Registers") == null) return false
        peripherals.addProperty("presentationHint", "peripherals")
        return true
    }

    /** The target stopped or ran: frame ids are no longer valid. */
    fun onRunStateChanged() {
        frameId = null
    }
}

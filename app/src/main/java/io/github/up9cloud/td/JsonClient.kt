package io.github.up9cloud.td

/**
 * JNI wrapper for TDLib JSON interface.
 * This class loads libtdjson.so and provides native methods for TDLib operations.
 */
class JsonClient {
    companion object {
        init {
            try {
                System.loadLibrary("tdjson")
                android.util.Log.d("JsonClient", "Loaded libtdjson.so successfully")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("JsonClient", "Failed to load libtdjson.so", e)
                e.printStackTrace()
            }
        }

        @JvmStatic
        external fun td_create_client_id(): Int

        @JvmStatic
        external fun td_send(clientId: Int, request: String)

        @JvmStatic
        external fun td_receive(timeout: Double): String?

        @JvmStatic
        external fun td_execute(request: String): String?

        // Legacy client API (for backward compatibility)
        @JvmStatic
        external fun td_json_client_create(): Long

        @JvmStatic
        external fun td_json_client_send(client: Long, request: String)

        @JvmStatic
        external fun td_json_client_receive(client: Long, timeout: Double): String?

        @JvmStatic
        external fun td_json_client_execute(client: Long, request: String): String?

        @JvmStatic
        external fun td_json_client_destroy(client: Long)
    }

    interface LogMessageCallback {
        fun call(verbosityLevel: Int, message: String)
    }
}

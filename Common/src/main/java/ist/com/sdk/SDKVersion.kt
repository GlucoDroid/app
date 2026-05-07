package ist.com.sdk

/**
 * Version metadata returned by `AlgorithmTools.version`.
 * Field names mirror the vendor SDK so the JNI can populate via setters.
 */
class SDKVersion {
    var version: String? = null
    var buildDate: String? = null
    var algorithm: Int = 0
}

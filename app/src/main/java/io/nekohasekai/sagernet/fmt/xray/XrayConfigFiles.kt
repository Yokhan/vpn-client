package io.nekohasekai.sagernet.fmt.xray

import java.io.File

/**
 * Writes the child config next to the other sidecar configs (`cacheDir/tmpcfg`) and removes it on
 * stop. The file necessarily contains credentials, so it is created inside the app cache, is only
 * referenced by path in logs, and is deleted before the process is considered stopped.
 */
object XrayConfigFiles {

    private const val PREFIX = "xray_"
    private const val SUFFIX = ".json"

    fun write(configDir: File, spec: XrayRouteSpec, token: Long): File {
        val json = XrayConfigBuilder.build(spec)
        configDir.mkdirs()
        val file = File(configDir, "$PREFIX$token$SUFFIX")
        file.writeText(json)
        return file
    }

    fun delete(file: File?) {
        if (file != null && file.exists()) {
            file.delete()
        }
    }
}

package com.wahana.plugingps

import com.getcapacitor.Logger

class WahanaPlugin {

    fun echo(value: String): String {
        Logger.info("Echo", value)

        return value
    }
}

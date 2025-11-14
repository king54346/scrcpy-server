package com.genymobile.scrcpy

interface AsyncProcessor {
    fun interface TerminationListener {
        /**
         * Notify processor termination
         *
         * @param fatalError `true` if this must cause the termination of the whole scrcpy-server.
         */
        fun onTerminated(fatalError: Boolean)
    }

    fun start(listener: TerminationListener?)

    fun stop()

    @Throws(InterruptedException::class)
    fun join()
}

package com.genymobile.scrcpy.control

import com.genymobile.scrcpy.util.Ln.d
import com.genymobile.scrcpy.util.Ln.w
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue

class DeviceMessageSender(private val controlChannel: ControlChannel) {
    private var thread: Thread? = null
    private val queue: BlockingQueue<DeviceMessage> = ArrayBlockingQueue(16)

    fun send(msg: DeviceMessage) {
        if (!queue.offer(msg)) {
            w("Device message dropped: " + msg.type)
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun loop() {
        while (!Thread.currentThread().isInterrupted) {
            val msg = queue.take()
            controlChannel.send(msg)
        }
    }

    fun start() {
        thread = Thread({
            try {
                loop()
            } catch (e: IOException) {
                // this is expected on close
            } catch (_: InterruptedException) {
            } finally {
                d("Device message sender stopped")
            }
        }, "control-send")
        thread!!.start()
    }

    fun stop() {
        if (thread != null) {
            thread!!.interrupt()
        }
    }

    @Throws(InterruptedException::class)
    fun join() {
        if (thread != null) {
            thread!!.join()
        }
    }
}
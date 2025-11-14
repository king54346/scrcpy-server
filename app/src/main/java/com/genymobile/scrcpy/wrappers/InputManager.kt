package com.genymobile.scrcpy.wrappers

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.hardware.input.InputManager as AndroidInputManager
import android.view.InputEvent
import android.view.MotionEvent
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext
import com.genymobile.scrcpy.util.Ln
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class InputManager private constructor(private val manager: AndroidInputManager) {

    private var lastPermissionLogDate: Long = 0

    fun injectInputEvent(inputEvent: InputEvent, mode: Int): Boolean {
        return try {
            val method = getInjectInputEventMethod()
            method.invoke(manager, inputEvent, mode) as Boolean
        } catch (e: ReflectiveOperationException) {
            if (e is InvocationTargetException) {
                val cause = e.cause
                if (cause is SecurityException) {
                    val message = cause.message
                    if (message != null && message.contains("INJECT_EVENTS permission")) {
                        // Do not flood the console, limit to one permission error log every 3 seconds
                        val now = System.currentTimeMillis()
                        if (lastPermissionLogDate <= now - 3000) {
                            Ln.e(message)
                            Ln.e("Make sure you have enabled \"USB debugging (Security Settings)\" and then rebooted your device.")
                            lastPermissionLogDate = now
                        }
                        // Do not print the stack trace
                        return false
                    }
                }
            }
            Ln.e("Could not invoke method", e)
            false
        }
    }

    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    fun addUniqueIdAssociationByPort(inputPort: String, uniqueId: String) {
        try {
            val method = getAddUniqueIdAssociationByPortMethod()
            method.invoke(manager, inputPort, uniqueId)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Cannot add unique id association by port", e)
        }
    }

    @TargetApi(AndroidVersions.API_35_ANDROID_15)
    fun removeUniqueIdAssociationByPort(inputPort: String) {
        try {
            val method = getRemoveUniqueIdAssociationByPortMethod()
            method.invoke(manager, inputPort)
        } catch (e: ReflectiveOperationException) {
            Ln.e("Cannot remove unique id association by port", e)
        }
    }

    companion object {
        const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
        const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2

        private var injectInputEventMethod: Method? = null
        private var setDisplayIdMethod: Method? = null
        private var setActionButtonMethod: Method? = null
        private var addUniqueIdAssociationByPortMethod: Method? = null
        private var removeUniqueIdAssociationByPortMethod: Method? = null

        @JvmStatic
        fun create(): InputManager {
            val manager = FakeContext.get()
                .getSystemService(FakeContext.INPUT_SERVICE) as AndroidInputManager
            return InputManager(manager)
        }

        @Throws(NoSuchMethodException::class)
        private fun getInjectInputEventMethod(): Method {
            if (injectInputEventMethod == null) {
                injectInputEventMethod = AndroidInputManager::class.java.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
            }
            return injectInputEventMethod!!
        }

        @Throws(NoSuchMethodException::class)
        private fun getSetDisplayIdMethod(): Method {
            if (setDisplayIdMethod == null) {
                setDisplayIdMethod = InputEvent::class.java.getMethod(
                    "setDisplayId",
                    Int::class.javaPrimitiveType
                )
            }
            return setDisplayIdMethod!!
        }

        @JvmStatic
        fun setDisplayId(inputEvent: InputEvent, displayId: Int): Boolean {
            return try {
                val method = getSetDisplayIdMethod()
                method.invoke(inputEvent, displayId)
                true
            } catch (e: ReflectiveOperationException) {
                Ln.e("Cannot associate a display id to the input event", e)
                false
            }
        }

        @Throws(NoSuchMethodException::class)
        private fun getSetActionButtonMethod(): Method {
            if (setActionButtonMethod == null) {
                setActionButtonMethod = MotionEvent::class.java.getMethod(
                    "setActionButton",
                    Int::class.javaPrimitiveType
                )
            }
            return setActionButtonMethod!!
        }

        @JvmStatic
        fun setActionButton(motionEvent: MotionEvent, actionButton: Int): Boolean {
            return try {
                val method = getSetActionButtonMethod()
                method.invoke(motionEvent, actionButton)
                true
            } catch (e: ReflectiveOperationException) {
                Ln.e("Cannot set action button on MotionEvent", e)
                false
            }
        }

        @Throws(NoSuchMethodException::class)
        private fun getAddUniqueIdAssociationByPortMethod(): Method {
            if (addUniqueIdAssociationByPortMethod == null) {
                addUniqueIdAssociationByPortMethod = AndroidInputManager::class.java.getMethod(
                    "addUniqueIdAssociationByPort",
                    String::class.java,
                    String::class.java
                )
            }
            return addUniqueIdAssociationByPortMethod!!
        }

        @Throws(NoSuchMethodException::class)
        private fun getRemoveUniqueIdAssociationByPortMethod(): Method {
            if (removeUniqueIdAssociationByPortMethod == null) {
                removeUniqueIdAssociationByPortMethod = AndroidInputManager::class.java.getMethod(
                    "removeUniqueIdAssociationByPort",
                    String::class.java
                )
            }
            return removeUniqueIdAssociationByPortMethod!!
        }
    }
}
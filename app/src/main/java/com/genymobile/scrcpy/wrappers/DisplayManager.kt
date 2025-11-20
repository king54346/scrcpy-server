package com.genymobile.scrcpy.wrappers

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.view.Display
import android.view.Surface
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext
import com.genymobile.scrcpy.device.DisplayInfo
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.Command
import com.genymobile.scrcpy.util.Ln
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.regex.Pattern

/**
 * DisplayManager 包装类
 * 通过反射调用系统 DisplayManagerGlobal 来管理显示器和虚拟显示器
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
class DisplayManager private constructor(
    private val manager: Any
) {

    /**
     * 显示器变化监听器
     */
    fun interface DisplayListener {
        fun onDisplayChanged(displayId: Int)
    }

    /**
     * 监听器句柄,用于注销监听
     */
    class DisplayListenerHandle internal constructor(
        val displayListenerProxy: Any
    )

    // ==================== 反射方法缓存 ====================

    private val methodCache = MethodCache()

    private class MethodCache {
        private val lock = Any()
        private var getDisplayInfo: Method? = null
        private var createVirtualDisplay: Method? = null
        private var requestDisplayPower: Method? = null

        fun getDisplayInfo(manager: Any): Method = synchronized(lock) {
            getDisplayInfo ?: manager.javaClass
                .getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .also { getDisplayInfo = it }
        }

        fun createVirtualDisplay(): Method = synchronized(lock) {
            createVirtualDisplay ?: android.hardware.display.DisplayManager::class.java
                .getMethod(
                    "createVirtualDisplay",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Surface::class.java
                )
                .also { createVirtualDisplay = it }
        }

        fun requestDisplayPower(manager: Any): Method = synchronized(lock) {
            requestDisplayPower ?: manager.javaClass
                .getMethod(
                    "requestDisplayPower",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                .also { requestDisplayPower = it }
        }
    }

    // ==================== 显示器信息获取 ====================

    /**
     * 获取指定显示器的详细信息
     */
    fun getDisplayInfo(displayId: Int): DisplayInfo? {
        return try {
            getDisplayInfoViaReflection(displayId)
        } catch (e: ReflectiveOperationException) {
            Ln.w("Failed to get DisplayInfo via reflection, falling back to dumpsys", e)
            getDisplayInfoFromDumpsys(displayId)
        } catch (e: Exception) {
            Ln.e("Failed to get DisplayInfo for display $displayId", e)
            null
        }
    }

    /**
     * 通过反射获取 DisplayInfo
     */
    private fun getDisplayInfoViaReflection(displayId: Int): DisplayInfo? {
        val method = methodCache.getDisplayInfo(manager)
        val displayInfo = method.invoke(manager, displayId)
            ?: return getDisplayInfoFromDumpsys(displayId)

        return extractDisplayInfo(displayInfo, displayId)
    }

    /**
     * 从系统 DisplayInfo 对象提取信息
     */
    private fun extractDisplayInfo(displayInfo: Any, displayId: Int): DisplayInfo {
        val cls = displayInfo.javaClass
        return DisplayInfo(
            displayId = displayId,
            size = Size(
                cls.getDeclaredField("logicalWidth").getInt(displayInfo),
                cls.getDeclaredField("logicalHeight").getInt(displayInfo)
            ),
            rotation = cls.getDeclaredField("rotation").getInt(displayInfo),
            layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo),
            flags = cls.getDeclaredField("flags").getInt(displayInfo),
            dpi = cls.getDeclaredField("logicalDensityDpi").getInt(displayInfo),
            uniqueId = cls.getDeclaredField("uniqueId")[displayInfo] as String
        )
    }

    /**
     * 获取所有显示器 ID
     */
    val displayIds: IntArray
        get() = try {
            manager.javaClass.getMethod("getDisplayIds").invoke(manager) as IntArray
        } catch (e: ReflectiveOperationException) {
            Ln.e("Failed to get display IDs", e)
            intArrayOf()
        }

    // ==================== 虚拟显示器创建 ====================

    /**
     * 创建镜像虚拟显示器 (使用静态系统 API)
     *  displayIdToMirror：要被镜像的显示器的 displayId，虚拟显示会显示这个显示器的内容
     *  创建一个虚拟显示（VirtualDisplay），其内容会镜像（复制）指定的另一个显示器的内容
     *  dm 调用不了SystemApi, @RequiresPermission(Manifest.permission.CAPTURE_VIDEO_OUTPUT) @Nullable @SystemApi  public static VirtualDisplay createVirtualDisplay(@NonNull String name, int width, int height, int displayIdToMirror, @Nullable Surface surface)
     *  通过使用 MediaProjection 授权创建虚拟显示
     *  或者通过直接反射dm的createVirtualDisplay(@NonNull String name, int width, int height, int displayIdToMirror, @Nullable Surface surface)去实现
     *
     * @param name 虚拟显示器名称
     * @param width 宽度
     * @param height 高度
     * @param displayIdToMirror 要镜像的显示器 ID
     * @param surface 输出 Surface
     * @return 虚拟显示器实例
     * @throws Exception 如果创建失败
     */
    @Throws(Exception::class)
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        displayIdToMirror: Int,
        surface: Surface?
    ): VirtualDisplay {
        val method = methodCache.createVirtualDisplay()
        return method.invoke(null, name, width, height, displayIdToMirror, surface)
                as VirtualDisplay
    }

    /**
     * 创建新的虚拟显示器 (使用 DisplayManager 实例)
     * 多一个flags
     * flags – A combination of virtual display flags: VIRTUAL_DISPLAY_FLAG_PUBLIC, VIRTUAL_DISPLAY_FLAG_PRESENTATION, VIRTUAL_DISPLAY_FLAG_SECURE, VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, or VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR.
     * @param name 虚拟显示器名称
     * @param width 宽度
     * @param height 高度
     * @param dpi 像素密度
     * @param surface 输出 Surface
     * @param flags 虚拟显示器标志位
     * @return 虚拟显示器实例
     * @throws Exception 如果创建失败
     */
    @Throws(Exception::class)
    fun createNewVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?,
        flags: Int = 0
    ): VirtualDisplay {
        val dm = createDisplayManagerInstance()
        return dm.createVirtualDisplay(name, width, height, dpi, surface, flags)
    }

    /**
     * 通过反射创建 DisplayManager 实例
     */
    private fun createDisplayManagerInstance(): android.hardware.display.DisplayManager {
        val ctor = android.hardware.display.DisplayManager::class.java
            .getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(FakeContext.get())
    }

    // ==================== 显示器电源控制 ====================

    /**
     * 控制显示器电源状态 (Android 15+)
     */
    @RequiresApi(AndroidVersions.API_35_ANDROID_15)
    fun requestDisplayPower(displayId: Int, on: Boolean): Boolean {
        return try {
            val method = methodCache.requestDisplayPower(manager)
            method.invoke(manager, displayId, on) as Boolean
        } catch (e: ReflectiveOperationException) {
            Ln.e("Failed to request display power", e)
            false
        }
    }

    // ==================== 显示器监听器 ====================

    /**
     * 注册显示器变化监听器
     */
    fun registerDisplayListener(
        listener: DisplayListener,
        handler: Handler? = null
    ): DisplayListenerHandle? {
        return try {
            val displayListenerClass = Class.forName(
                "android.hardware.display.DisplayManager\$DisplayListener"
            )
            val proxy = createDisplayListenerProxy(displayListenerClass, listener)

            registerProxyWithManager(displayListenerClass, proxy, handler)

            DisplayListenerHandle(proxy)
        } catch (e: Exception) {
            Ln.e("Failed to register display listener", e)
            null
        }
    }

    /**
     * 创建 DisplayListener 动态代理
     */
    private fun createDisplayListenerProxy(
        interfaceClass: Class<*>,
        listener: DisplayListener
    ): Any {
        return Proxy.newProxyInstance(
            ClassLoader.getSystemClassLoader(),
            arrayOf(interfaceClass)
        ) { _, method, args ->
            when (method.name) {
                "onDisplayChanged" -> listener.onDisplayChanged(args[0] as Int)
                "toString" -> "DisplayListener"
                else -> null
            }
        }
    }

    /**
     * 将代理注册到 DisplayManagerGlobal
     * 尝试多种签名以兼容不同 Android 版本
     */
    private fun registerProxyWithManager(
        listenerClass: Class<*>,
        proxy: Any,
        handler: Handler?
    ) {
        val signatures = listOf(
            // Android 14+: 带包名参数
            listOf(
                listenerClass,
                Handler::class.java,
                Long::class.javaPrimitiveType,
                String::class.java
            ) to arrayOf(proxy, handler, EVENT_FLAG_DISPLAY_CHANGED, FakeContext.PACKAGE_NAME),

            // Android 13: 带 eventsMask 参数
            listOf(
                listenerClass,
                Handler::class.java,
                Long::class.javaPrimitiveType
            ) to arrayOf(proxy, handler, EVENT_FLAG_DISPLAY_CHANGED),

            // Android 12-: 基础签名
            listOf(
                listenerClass,
                Handler::class.java
            ) to arrayOf(proxy, handler)
        )

        var lastException: Exception? = null
        for ((paramTypes, args) in signatures) {
            try {
                manager.javaClass
                    .getMethod("registerDisplayListener", *paramTypes.toTypedArray())
                    .invoke(manager, *args)
                return
            } catch (e: NoSuchMethodException) {
                lastException = e
                continue
            }
        }

        throw lastException ?: IllegalStateException("No compatible registerDisplayListener method found")
    }

    /**
     * 注销显示器变化监听器
     */
    fun unregisterDisplayListener(handle: DisplayListenerHandle) {
        try {
            val displayListenerClass = Class.forName(
                "android.hardware.display.DisplayManager\$DisplayListener"
            )
            manager.javaClass
                .getMethod("unregisterDisplayListener", displayListenerClass)
                .invoke(manager, handle.displayListenerProxy)
        } catch (e: Exception) {
            Ln.e("Failed to unregister display listener", e)
        }
    }

    // ==================== 伴生对象 ====================

    companion object {
        private const val EVENT_FLAG_DISPLAY_CHANGED: Long = 1L shl 2

        /**
         * 创建 DisplayManager 实例
         */
        fun create(): DisplayManager {
            return try {
                val clazz = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val getInstance = clazz.getDeclaredMethod("getInstance")
                val dmg = getInstance.invoke(null)
                    ?: throw IllegalStateException("DisplayManagerGlobal.getInstance() returned null")
                DisplayManager(dmg)
            } catch (e: ReflectiveOperationException) {
                throw AssertionError("Failed to create DisplayManager", e)
            }
        }

        /**
         * 从 dumpsys display 输出获取 DisplayInfo (备用方案)
         */
        private fun getDisplayInfoFromDumpsys(displayId: Int): DisplayInfo? {
            return try {
                val output = Command.execReadOutput("dumpsys", "display")
                parseDisplayInfo(output, displayId)
            } catch (e: Exception) {
                Ln.e("Failed to get DisplayInfo from dumpsys", e)
                null
            }
        }

        /**
         * 解析 dumpsys display 输出
         *
         * 示例输出:
         * mOverrideDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS,
         *   real 1080 x 2400, rotation 0, density 440, layerStack 0}
         */
        fun parseDisplayInfo(dumpsysOutput: String, displayId: Int): DisplayInfo? {
            val pattern = Regex(
                """
                ^    mOverrideDisplayInfo=DisplayInfo\{
                ".*?,\s+displayId\s+$displayId
                .*?
                (,\s+FLAG_.*?)?           # 可选的 flags
                ,\s+real\s+(\d+)\s+x\s+(\d+)  # 分辨率
                .*?
                ,\s+rotation\s+(\d+)      # 旋转
                .*?
                ,\s+density\s+(\d+)       # 密度
                .*?
                ,\s+layerStack\s+(\d+)    # 图层栈
                """.trimIndent(),
                setOf(RegexOption.MULTILINE, RegexOption.COMMENTS)
            )

            val match = pattern.find(dumpsysOutput) ?: return null

            return DisplayInfo(
                displayId = displayId,
                size = Size(
                    width = match.groupValues[2].toInt(),
                    height = match.groupValues[3].toInt()
                ),
                rotation = match.groupValues[4].toInt(),
                layerStack = match.groupValues[6].toInt(),
                flags = parseDisplayFlags(match.groups[1]?.value),
                dpi = match.groupValues[5].toInt(),
                uniqueId = null
            )
        }

        /**
         * 解析显示器标志位字符串
         */
        private fun parseDisplayFlags(flagsText: String?): Int {
            if (flagsText.isNullOrBlank()) {
                return 0
            }

            var flags = 0
            val pattern = Pattern.compile("FLAG_[A-Z_]+")
            val matcher = pattern.matcher(flagsText)

            while (matcher.find()) {
                val flagName = matcher.group()
                try {
                    val field = Display::class.java.getDeclaredField(flagName)
                    flags = flags or field.getInt(null)
                } catch (e: ReflectiveOperationException) {
                    // 忽略 @TestApi 标志
                    Ln.d("Unknown display flag: $flagName")
                }
            }

            return flags
        }
    }
}
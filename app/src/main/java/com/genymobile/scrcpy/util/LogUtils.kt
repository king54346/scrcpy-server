package com.genymobile.scrcpy.util

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Range
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.audio.AudioCodec
import com.genymobile.scrcpy.device.Device.listApps
import com.genymobile.scrcpy.device.DeviceApp
import com.genymobile.scrcpy.util.CodecUtils.getEncoders
import com.genymobile.scrcpy.util.Ln.w
import com.genymobile.scrcpy.video.VideoCodec
import com.genymobile.scrcpy.wrappers.ServiceManager.cameraManager
import com.genymobile.scrcpy.wrappers.ServiceManager.displayManager
import java.util.Collections
import java.util.Objects
import java.util.SortedSet
import java.util.TreeSet

object LogUtils {
    private fun buildEncoderListMessage(type: String, codecs: Array<Codec>): String {
        val builder = StringBuilder("List of ").append(type).append(" encoders:")
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (codec in codecs) {
            val encoders = getEncoders(
                codecList,
                codec.mimeType!!
            )
            for (info in encoders) {
                val lineStart = builder.length
                builder.append("\n    --").append(type).append("-codec=").append(codec.codecName)
                builder.append(" --").append(type).append("-encoder=").append(info.name)
                if (Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10) {
                    val lineLength = builder.length - lineStart
                    val column = 70
                    if (lineLength < column) {
                        val padding = column - lineLength
                        builder.append(String.format("%" + padding + "s", " "))
                    }
                    builder.append(" (").append(getHwCodecType(info)).append(')')
                    if (info.isVendor) {
                        builder.append(" [vendor]")
                    }
                    if (info.isAlias) {
                        builder.append(" (alias for ").append(info.canonicalName).append(')')
                    }
                }
            }
        }

        return builder.toString()
    }

    fun buildVideoEncoderListMessage(): String {
        return buildEncoderListMessage("video", VideoCodec.entries.toTypedArray())
    }

    fun buildAudioEncoderListMessage(): String {
        return buildEncoderListMessage("audio", AudioCodec.entries.toTypedArray())
    }

    @TargetApi(AndroidVersions.API_29_ANDROID_10)
    private fun getHwCodecType(info: MediaCodecInfo): String {
        if (info.isSoftwareOnly) {
            return "sw"
        }
        if (info.isHardwareAccelerated) {
            return "hw"
        }
        return "hybrid"
    }

    fun buildDisplayListMessage(): String {
        val builder = StringBuilder("List of displays:")
        val displayManager = displayManager
        val displayIds = displayManager!!.displayIds
        if (displayIds == null || displayIds.size == 0) {
            builder.append("\n    (none)")
        } else {
            for (id in displayIds) {
                builder.append("\n    --display-id=").append(id).append("    (")
                val displayInfo = displayManager.getDisplayInfo(id)
                if (displayInfo != null) {
                    val size = displayInfo.size
                    builder.append(size.width).append("x").append(size.height)
                } else {
                    builder.append("size unknown")
                }
                builder.append(")")
            }
        }
        return builder.toString()
    }

    private fun getCameraFacingName(facing: Int): String {
        return when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unknown"
        }
    }

    private fun isCameraBackwardCompatible(characteristics: CameraCharacteristics): Boolean {
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return false

        for (capability in capabilities) {
            if (capability == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) {
                return true
            }
        }

        return false
    }

    fun buildCameraListMessage(includeSizes: Boolean): String {
        val builder = StringBuilder("List of cameras:")
        val cameraManager = cameraManager
        try {
            val cameraIds = cameraManager!!.cameraIdList
            if (cameraIds.size == 0) {
                builder.append("\n    (none)")
            } else {
                for (id in cameraIds) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)

                    if (!isCameraBackwardCompatible(characteristics)) {
                        // Ignore depth cameras as suggested by official documentation
                        // <https://developer.android.com/media/camera/camera2/camera-enumeration>
                        continue
                    }

                    builder.append("\n    --camera-id=").append(id)

                    val facing = characteristics.get(CameraCharacteristics.LENS_FACING)!!
                    builder.append("    (").append(getCameraFacingName(facing)).append(", ")

                    val activeSize =
                        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    builder.append(activeSize!!.width()).append("x").append(activeSize.height())

                    try {
                        // Capture frame rates for low-FPS mode are the same for every resolution
                        val lowFpsRanges =
                            characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                        if (lowFpsRanges != null) {
                            val uniqueLowFps = getUniqueSet(lowFpsRanges)
                            builder.append(", fps=").append(uniqueLowFps)
                        }
                    } catch (e: Exception) {
                        // Some devices may provide invalid ranges, causing an IllegalArgumentException "lower must be less than or equal to upper"
                        w("Could not get available frame rates for camera $id", e)
                    }

                    builder.append(')')

                    if (includeSizes) {
                        val configs =
                            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                        val sizes = configs!!.getOutputSizes(
                            MediaCodec::class.java
                        )
                        if (sizes == null || sizes.size == 0) {
                            builder.append("\n        (none)")
                        } else {
                            for (size in sizes) {
                                builder.append("\n        - ").append(size.width).append('x')
                                    .append(size.height)
                            }
                        }

                        val highSpeedSizes = configs.highSpeedVideoSizes
                        if (highSpeedSizes != null && highSpeedSizes.size > 0) {
                            builder.append("\n      High speed capture (--camera-high-speed):")
                            for (size in highSpeedSizes) {
                                val highFpsRanges = configs.highSpeedVideoFpsRanges
                                val uniqueHighFps = getUniqueSet(highFpsRanges)
                                builder.append("\n        - ").append(size.width).append("x")
                                    .append(size.height)
                                builder.append(" (fps=").append(uniqueHighFps).append(')')
                            }
                        }
                    }
                }
            }
        } catch (e: CameraAccessException) {
            builder.append("\n    (access denied)")
        }
        return builder.toString()
    }

    private fun getUniqueSet(ranges: Array<Range<Int>>): SortedSet<Int> {
        val set: SortedSet<Int> = TreeSet()
        for (range in ranges) {
            set.add(range.upper)
        }
        return set
    }


    fun buildAppListMessage(): String {
        val apps = listApps()
        return buildAppListMessage("List of apps:", apps)
    }

    @SuppressLint("QueryPermissionsNeeded")
    fun buildAppListMessage(title: String, apps: List<DeviceApp>): String {
        val builder = StringBuilder(title)

        // Sort by:
        //  1. system flag (system apps are before non-system apps)
        //  2. name
        //  3. package name
        // Comparator.comparing() was introduced in API 24, so it cannot be used here to simplify the code
        Collections.sort(apps) { thisApp: DeviceApp, otherApp: DeviceApp ->
            // System apps first
            var cmp = -java.lang.Boolean.compare(thisApp.isSystem, otherApp.isSystem)
            if (cmp != 0) {
                return@sort cmp
            }

            cmp = Objects.compare(
                thisApp.name, otherApp.name
            ) { obj: String, s: String? ->
                obj.compareTo(
                    s!!
                )
            }
            if (cmp != 0) {
                return@sort cmp
            }
            Objects.compare(
                thisApp.packageName, otherApp.packageName
            ) { obj: String, s: String? ->
                obj.compareTo(
                    s!!
                )
            }
        }

        val column = 30
        for (app in apps) {
            val name = app.name
            val padding = column - name.length
            builder.append("\n ")
            if (app.isSystem) {
                builder.append("* ")
            } else {
                builder.append("- ")
            }
            builder.append(name)
            if (padding > 0) {
                builder.append(String.format("%" + padding + "s", " "))
            } else {
                builder.append("\n   ").append(String.format("%" + column + "s", " "))
            }
            builder.append(" ").append(app.packageName)
        }

        return builder.toString()
    }
}
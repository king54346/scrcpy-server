package com.genymobile.scrcpy

import android.graphics.Rect
import android.util.Pair
import com.genymobile.scrcpy.BuildConfig
import com.genymobile.scrcpy.audio.AudioCodec
import com.genymobile.scrcpy.audio.AudioSource
import com.genymobile.scrcpy.device.Device
import com.genymobile.scrcpy.device.NewDisplay
import com.genymobile.scrcpy.device.Orientation
import com.genymobile.scrcpy.device.Size
import com.genymobile.scrcpy.util.CodecOption
import com.genymobile.scrcpy.util.Ln
import com.genymobile.scrcpy.video.CameraAspectRatio
import com.genymobile.scrcpy.video.CameraFacing
import com.genymobile.scrcpy.video.VideoCodec
import com.genymobile.scrcpy.video.VideoSource
import com.genymobile.scrcpy.wrappers.WindowManager

class Options {
    var logLevel: Ln.Level = Ln.Level.DEBUG
        private set
    var scid: Int = -1 // 31-bit non-negative value, or -1
        private set
    var video: Boolean = true
        private set
    var audio: Boolean = true
        private set
    var maxSize: Int = 0
        private set
    var videoCodec: VideoCodec = VideoCodec.H264
        private set
    var audioCodec: AudioCodec = AudioCodec.RAW
        private set
    var videoSource: VideoSource = VideoSource.DISPLAY
        private set
    var audioSource: AudioSource = AudioSource.OUTPUT
        private set
    var audioDup: Boolean = false
        private set
    var videoBitRate: Int = 8000000
        private set
    var audioBitRate: Int = 128000
        private set
    var maxFps: Float = 0f
        private set
    var angle: Float = 0f
        private set
    var isTunnelForward: Boolean = false
        private set
    var crop: Rect? = null
        private set
    var control: Boolean = true
        private set
    var displayId: Int = 0
        private set
    var cameraId: String? = null
        private set
    var cameraSize: Size? = null
        private set
    var cameraFacing: CameraFacing? = null
        private set
    var cameraAspectRatio: CameraAspectRatio? = null
        private set
    var cameraFps: Int = 0
        private set
    var cameraHighSpeed: Boolean = false
        private set
    var showTouches: Boolean = false
        private set
    var stayAwake: Boolean = false
        private set
    var screenOffTimeout: Int = -1
        private set
    var displayImePolicy: Int = -1
        private set
    var videoCodecOptions: List<CodecOption>? = null
        private set
    var audioCodecOptions: List<CodecOption>? = null
        private set

    var videoEncoder: String? = null
        private set
    var audioEncoder: String? = null
        private set
    var powerOffScreenOnClose: Boolean = false
        private set
    var clipboardAutosync: Boolean = true
        private set
    var downsizeOnError: Boolean = true
        private set
    var cleanup: Boolean = true
        private set
    var powerOn: Boolean = true
        private set

    var newDisplay: NewDisplay? = null
        private set
    var vDDestroyContent: Boolean = true
        private set
    var vDSystemDecorations: Boolean = true
        private set

    var captureOrientationLock: Orientation.Lock = Orientation.Lock.Unlocked
        private set
    var captureOrientation: Orientation = Orientation.Orient0
        private set

    var listEncoders: Boolean = false
        private set
    var listDisplays: Boolean = false
        private set
    var listCameras: Boolean = false
        private set
    var listCameraSizes: Boolean = false
        private set
    var listApps: Boolean = false
        private set

    // Options not used by the scrcpy client, but useful to use scrcpy-server directly
    var sendDeviceMeta: Boolean = true // send device name and size
        private set
    var sendFrameMeta: Boolean = true // send PTS so that the client may record properly
        private set
    var sendDummyByte: Boolean = true // write a byte on start to detect connection issues
        private set
    var sendCodecMeta: Boolean = true // write the codec metadata before the stream
        private set

    val list: Boolean
        get() = listEncoders || listDisplays || listCameras || listCameraSizes || listApps

    companion object {
        fun parse(args: Array<String>): Options {
            require(args.size >= 1) { "Missing client version" }

            val clientVersion = args[0]
            require(clientVersion == BuildConfig.VERSION_NAME) { "The server version (" + BuildConfig.VERSION_NAME + ") does not match the client " + "(" + clientVersion + ")" }

            val options = Options()

            for (i in 1..<args.size) {
                val arg = args[i]
                val equalIndex = arg.indexOf('=')
                require(equalIndex != -1) { "Invalid key=value pair: \"$arg\"" }
                val key = arg.substring(0, equalIndex)
                val value = arg.substring(equalIndex + 1)
                when (key) {
                    "scid" -> {
                        val scid = value.toInt(0x10)
                        require(scid >= -1) { "scid may not be negative (except -1 for 'none'): $scid" }
                        options.scid = scid
                    }

                    "log_level" -> options.logLevel = Ln.Level.valueOf(value.uppercase())
                    "video" -> options.video = value.toBoolean()
                    "audio" -> options.audio = value.toBoolean()
                    "video_codec" -> {
                        val videoCodec = VideoCodec.findByName(value)
                        requireNotNull(videoCodec) { "Video codec $value not supported" }
                        options.videoCodec = videoCodec
                    }

                    "audio_codec" -> {
                        val audioCodec = AudioCodec.findByName(value)
                        requireNotNull(audioCodec) { "Audio codec $value not supported" }
                        options.audioCodec = audioCodec
                    }

                    "video_source" -> {
                        val videoSource = VideoSource.findByName(value)
                        requireNotNull(videoSource) { "Video source $value not supported" }
                        options.videoSource = videoSource
                    }

                    "audio_source" -> {
                        val audioSource = AudioSource.findByName(value)
                        requireNotNull(audioSource) { "Audio source $value not supported" }
                        options.audioSource = audioSource
                    }

                    "audio_dup" -> options.audioDup = value.toBoolean()
                    "max_size" -> options.maxSize = value.toInt() and 7.inv() // multiple of 8
                    "video_bit_rate" -> options.videoBitRate = value.toInt()
                    "audio_bit_rate" -> options.audioBitRate = value.toInt()
                    "max_fps" -> options.maxFps = parseFloat("max_fps", value)
                    "angle" -> options.angle = parseFloat("angle", value)
                    "tunnel_forward" -> options.isTunnelForward = value.toBoolean()
                    "crop" -> if (!value.isEmpty()) {
                        options.crop = parseCrop(value)
                    }

                    "control" -> options.control = value.toBoolean()
                    "display_id" -> options.displayId = value.toInt()
                    "show_touches" -> options.showTouches = value.toBoolean()
                    "stay_awake" -> options.stayAwake = value.toBoolean()
                    "screen_off_timeout" -> {
                        options.screenOffTimeout = value.toInt()
                        require(options.screenOffTimeout >= -1) { "Invalid screen off timeout: " + options.screenOffTimeout }
                    }

                    "video_codec_options" -> options.videoCodecOptions = CodecOption.parse(value)
                    "audio_codec_options" -> options.audioCodecOptions = CodecOption.parse(value)
                    "video_encoder" -> if (!value.isEmpty()) {
                        options.videoEncoder = value
                    }

                    "audio_encoder" -> {
                        if (!value.isEmpty()) {
                            options.audioEncoder = value
                        }
                        options.powerOffScreenOnClose = value.toBoolean()
                    }

                    "power_off_on_close" -> options.powerOffScreenOnClose = value.toBoolean()
                    "clipboard_autosync" -> options.clipboardAutosync = value.toBoolean()
                    "downsize_on_error" -> options.downsizeOnError = value.toBoolean()
                    "cleanup" -> options.cleanup = value.toBoolean()
                    "power_on" -> options.powerOn = value.toBoolean()
                    "list_encoders" -> options.listEncoders = value.toBoolean()
                    "list_displays" -> options.listDisplays = value.toBoolean()
                    "list_cameras" -> options.listCameras = value.toBoolean()
                    "list_camera_sizes" -> options.listCameraSizes = value.toBoolean()
                    "list_apps" -> options.listApps = value.toBoolean()
                    "camera_id" -> if (!value.isEmpty()) {
                        options.cameraId = value
                    }

                    "camera_size" -> if (!value.isEmpty()) {
                        options.cameraSize = parseSize(value)
                    }

                    "camera_facing" -> if (!value.isEmpty()) {
                        val facing = CameraFacing.findByName(value)
                        requireNotNull(facing) { "Camera facing $value not supported" }
                        options.cameraFacing = facing
                    }

                    "camera_ar" -> if (!value.isEmpty()) {
                        options.cameraAspectRatio = parseCameraAspectRatio(value)
                    }

                    "camera_fps" -> options.cameraFps = value.toInt()
                    "camera_high_speed" -> options.cameraHighSpeed = value.toBoolean()
                    "new_display" -> options.newDisplay = parseNewDisplay(value)
                    "vd_destroy_content" -> options.vDDestroyContent = value.toBoolean()
                    "vd_system_decorations" -> options.vDSystemDecorations = value.toBoolean()
                    "capture_orientation" -> {
                        val pair = parseCaptureOrientation(value)
                        options.captureOrientationLock = pair.first
                        options.captureOrientation = pair.second
                    }

                    "display_ime_policy" -> options.displayImePolicy = parseDisplayImePolicy(value)
                    "send_device_meta" -> options.sendDeviceMeta = value.toBoolean()
                    "send_frame_meta" -> options.sendFrameMeta = value.toBoolean()
                    "send_dummy_byte" -> options.sendDummyByte = value.toBoolean()
                    "send_codec_meta" -> options.sendCodecMeta = value.toBoolean()
                    "raw_stream" -> {
                        val rawStream = value.toBoolean()
                        if (rawStream) {
                            options.sendDeviceMeta = false
                            options.sendFrameMeta = false
                            options.sendDummyByte = false
                            options.sendCodecMeta = false
                        }
                    }

                    else -> Ln.w("Unknown server option: $key")
                }
            }

            if (options.newDisplay != null) {
                assert(options.displayId == 0) { "Must not set both displayId and newDisplay" }
                options.displayId = Device.DISPLAY_ID_NONE
            }

            return options
        }

        private fun parseCrop(crop: String): Rect {
            // input format: "width:height:x:y"
            val tokens = crop.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            require(tokens.size == 4) { "Crop must contains 4 values separated by colons: \"$crop\"" }
            val width = tokens[0].toInt()
            val height = tokens[1].toInt()
            require(!(width <= 0 || height <= 0)) { "Invalid crop size: " + width + "x" + height }
            val x = tokens[2].toInt()
            val y = tokens[3].toInt()
            require(!(x < 0 || y < 0)) { "Invalid crop offset: $x:$y" }
            return Rect(x, y, x + width, y + height)
        }

        private fun parseSize(size: String): Size {
            // input format: "<width>x<height>"
            val tokens = size.split("x".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            require(tokens.size == 2) { "Invalid size format (expected <width>x<height>): \"$size\"" }
            val width = tokens[0].toInt()
            val height = tokens[1].toInt()
            require(!(width <= 0 || height <= 0)) { "Invalid non-positive size dimension: \"$size\"" }
            return Size(width, height)
        }

        private fun parseCameraAspectRatio(ar: String): CameraAspectRatio {
            if ("sensor" == ar) {
                return CameraAspectRatio.sensorAspectRatio()
            }

            val tokens = ar.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (tokens.size == 2) {
                val w = tokens[0].toInt()
                val h = tokens[1].toInt()
                return CameraAspectRatio.fromFraction(w, h)
            }

            val floatAr = tokens[0].toFloat()
            return CameraAspectRatio.fromFloat(floatAr)
        }

        private fun parseFloat(key: String, value: String): Float {
            try {
                return value.toFloat()
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Invalid float value for $key: \"$value\"")
            }
        }

        private fun parseNewDisplay(newDisplay: String): NewDisplay {
            // Possible inputs:
            //  - "" (empty string)
            //  - "<width>x<height>/<dpi>"
            //  - "<width>x<height>"
            //  - "/<dpi>"
            if (newDisplay.isEmpty()) {
                return NewDisplay()
            }

            val tokens =
                newDisplay.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val size = if (!tokens[0].isEmpty()) {
                parseSize(tokens[0])
            } else {
                null
            }

            val dpi: Int
            if (tokens.size >= 2) {
                dpi = tokens[1].toInt()
                require(dpi > 0) { "Invalid non-positive dpi: " + tokens[1] }
            } else {
                dpi = 0
            }

            return NewDisplay(size, dpi)
        }

        private fun parseCaptureOrientation(value: String): Pair<Orientation.Lock, Orientation> {
            var value = value
            require(!value.isEmpty()) { "Empty capture orientation string" }

            val lock: Orientation.Lock
            if (value[0] == '@') {
                // Consume '@'
                value = value.substring(1)
                if (value.isEmpty()) {
                    // Only '@': lock to the initial orientation (orientation is unused)
                    return Pair.create(Orientation.Lock.LockedInitial, Orientation.Orient0)
                }
                lock = Orientation.Lock.LockedValue
            } else {
                lock = Orientation.Lock.Unlocked
            }

            return Pair.create(lock, Orientation.getByName(value))
        }

        private fun parseDisplayImePolicy(value: String): Int {
            return when (value) {
                "local" -> WindowManager.DISPLAY_IME_POLICY_LOCAL
                "fallback" -> WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY
                "hide" -> WindowManager.DISPLAY_IME_POLICY_HIDE
                else -> throw IllegalArgumentException("Invalid display IME policy: $value")
            }
        }
    }
}
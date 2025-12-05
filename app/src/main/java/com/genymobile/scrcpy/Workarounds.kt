package com.genymobile.scrcpy

import android.annotation.SuppressLint
import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import android.os.Parcel
import androidx.annotation.RequiresApi
import com.genymobile.scrcpy.audio.AudioCaptureException
import com.genymobile.scrcpy.util.Ln
import java.lang.ref.WeakReference
import java.lang.reflect.Method

@SuppressLint("PrivateApi,BlockedPrivateApi,SoonBlockedPrivateApi,DiscouragedPrivateApi")
object Workarounds {
    private var ACTIVITY_THREAD_CLASS: Class<*>? = null
    private var ACTIVITY_THREAD: Any? = null

    init {
        try {
            // ActivityThread activityThread = new ActivityThread();
            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread")
            val activityThreadConstructor = ACTIVITY_THREAD_CLASS!!.getDeclaredConstructor()
            activityThreadConstructor.isAccessible = true
            ACTIVITY_THREAD = activityThreadConstructor.newInstance()

            // ActivityThread.sCurrentActivityThread = activityThread;
            val sCurrentActivityThreadField =
                ACTIVITY_THREAD_CLASS!!.getDeclaredField("sCurrentActivityThread")
            sCurrentActivityThreadField.isAccessible = true
            sCurrentActivityThreadField[null] = ACTIVITY_THREAD

            // activityThread.mSystemThread = true;
            val mSystemThreadField = ACTIVITY_THREAD_CLASS!!.getDeclaredField("mSystemThread")
            mSystemThreadField.isAccessible = true
            mSystemThreadField.setBoolean(ACTIVITY_THREAD, true)
        } catch (e: Exception) {
            throw AssertionError(e)
        }
    }

    fun apply() {
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
            // On some Samsung devices, DisplayManagerGlobal.getDisplayInfoLocked() calls ActivityThread.currentActivityThread().getConfiguration(),
            // which requires a non-null ConfigurationController.
            // ConfigurationController was introduced in Android 12, so do not attempt to set it on lower versions.
            // <https://github.com/Genymobile/scrcpy/issues/4467>
            // Must be called before fillAppContext() because it is necessary to get a valid system context.
            fillConfigurationController()
        }

        // On ONYX devices, fillAppInfo() breaks video mirroring:
        // <https://github.com/Genymobile/scrcpy/issues/5182>
        val mustFillAppInfo = !Build.BRAND.equals("ONYX", ignoreCase = true)

        if (mustFillAppInfo) {
            fillAppInfo()
        }

        fillAppContext()
    }

    private fun fillAppInfo() {
        try {
            val activityThreadClass = ACTIVITY_THREAD_CLASS ?: return

            // ActivityThread.AppBindData appBindData = new ActivityThread.AppBindData();
            val appBindDataClass = Class.forName("android.app.ActivityThread\$AppBindData")
            val appBindDataConstructor = appBindDataClass.getDeclaredConstructor()
            appBindDataConstructor.isAccessible = true
            val appBindData = appBindDataConstructor.newInstance()

            val applicationInfo = ApplicationInfo()
            applicationInfo.packageName = FakeContext.PACKAGE_NAME

            // appBindData.appInfo = applicationInfo;
            val appInfoField = appBindDataClass.getDeclaredField("appInfo")
            appInfoField.isAccessible = true
            appInfoField[appBindData] = applicationInfo

            // activityThread.mBoundApplication = appBindData;
            val mBoundApplicationField =
                activityThreadClass.getDeclaredField("mBoundApplication")
            mBoundApplicationField.isAccessible = true
            mBoundApplicationField[ACTIVITY_THREAD] = appBindData
        } catch (throwable: Throwable) {
            // this is a workaround, so failing is not an error
            Ln.d("Could not fill app info: " + throwable.message)
        }
    }

    private fun fillAppContext() {
        try {
            val activityThreadClass = ACTIVITY_THREAD_CLASS ?: return

            val app = Application()
            val baseField = ContextWrapper::class.java.getDeclaredField("mBase")
            baseField.isAccessible = true
            baseField[app] = FakeContext.get()

            // activityThread.mInitialApplication = app;
            val mInitialApplicationField =
                activityThreadClass.getDeclaredField("mInitialApplication")
            mInitialApplicationField.isAccessible = true
            mInitialApplicationField[ACTIVITY_THREAD] = app
        } catch (throwable: Throwable) {
            // this is a workaround, so failing is not an error
            Ln.d("Could not fill app context: " + throwable.message)
        }
    }

    private fun fillConfigurationController() {
        try {
            val activityThreadClass = ACTIVITY_THREAD_CLASS ?: return

            val configurationControllerClass = Class.forName("android.app.ConfigurationController")
            val activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal")

            // configurationController = new ConfigurationController(ACTIVITY_THREAD);
            val configurationControllerConstructor =
                configurationControllerClass.getDeclaredConstructor(activityThreadInternalClass)
            configurationControllerConstructor.isAccessible = true
            val configurationController = configurationControllerConstructor.newInstance(
                ACTIVITY_THREAD
            )

            // ACTIVITY_THREAD.mConfigurationController = configurationController;
            val configurationControllerField =
                activityThreadClass.getDeclaredField("mConfigurationController")
            configurationControllerField.isAccessible = true
            configurationControllerField[ACTIVITY_THREAD] = configurationController
        } catch (throwable: Throwable) {
            Ln.d("Could not fill configuration: " + throwable.message)
        }
    }

    val systemContext: Context?
        get() {
            try {
                val activityThreadClass = ACTIVITY_THREAD_CLASS ?: return null

                val getSystemContextMethod =
                    activityThreadClass.getDeclaredMethod("getSystemContext")
                return getSystemContextMethod.invoke(ACTIVITY_THREAD) as Context
            } catch (throwable: Throwable) {
                // this is a workaround, so failing is not an error
                Ln.d("Could not get system context: " + throwable.message)
                return null
            }
        }

    @RequiresApi(AndroidVersions.API_30_ANDROID_11)
    @SuppressLint("WrongConstant,MissingPermission")
    @Throws(
        AudioCaptureException::class
    )
    @JvmStatic
    fun createAudioRecord(
        source: Int,
        sampleRate: Int,
        channelConfig: Int,
        channels: Int,
        channelMask: Int,
        encoding: Int
    ): AudioRecord {
        // Vivo (and maybe some other third-party ROMs) modified `AudioRecord`'s constructor, requiring `Context`s from real App environment.
        //
        // This method invokes the `AudioRecord(long nativeRecordInJavaObj)` constructor to create an empty `AudioRecord` instance, then uses
        // reflections to initialize it like the normal constructor do (or the `AudioRecord.Builder.build()` method do).
        // As a result, the modified code was not executed.
        try {
            // AudioRecord audioRecord = new AudioRecord(0L);
            val audioRecordConstructor =
                AudioRecord::class.java.getDeclaredConstructor(Long::class.javaPrimitiveType)
            audioRecordConstructor.isAccessible = true
            val audioRecord = audioRecordConstructor.newInstance(0L)

            // audioRecord.mRecordingState = RECORDSTATE_STOPPED;
            val mRecordingStateField = AudioRecord::class.java.getDeclaredField("mRecordingState")
            mRecordingStateField.isAccessible = true
            mRecordingStateField[audioRecord] = AudioRecord.RECORDSTATE_STOPPED

            var looper = Looper.myLooper()
            if (looper == null) {
                looper = Looper.getMainLooper()
            }

            // audioRecord.mInitializationLooper = looper;
            val mInitializationLooperField =
                AudioRecord::class.java.getDeclaredField("mInitializationLooper")
            mInitializationLooperField.isAccessible = true
            mInitializationLooperField[audioRecord] = looper

            // Create `AudioAttributes` with fixed capture preset
            val capturePreset = source
            val audioAttributesBuilder = AudioAttributes.Builder()
            val setInternalCapturePresetMethod =
                AudioAttributes.Builder::class.java.getMethod(
                    "setInternalCapturePreset",
                    Int::class.javaPrimitiveType
                )
            setInternalCapturePresetMethod.invoke(audioAttributesBuilder, capturePreset)
            val attributes = audioAttributesBuilder.build()

            // audioRecord.mAudioAttributes = attributes;
            val mAudioAttributesField = AudioRecord::class.java.getDeclaredField("mAudioAttributes")
            mAudioAttributesField.isAccessible = true
            mAudioAttributesField[audioRecord] = attributes

            // audioRecord.audioParamCheck(capturePreset, sampleRate, encoding);
            val audioParamCheckMethod = AudioRecord::class.java.getDeclaredMethod(
                "audioParamCheck",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            audioParamCheckMethod.isAccessible = true
            audioParamCheckMethod.invoke(audioRecord, capturePreset, sampleRate, encoding)

            // audioRecord.mChannelCount = channels
            val mChannelCountField = AudioRecord::class.java.getDeclaredField("mChannelCount")
            mChannelCountField.isAccessible = true
            mChannelCountField[audioRecord] = channels

            // audioRecord.mChannelMask = channelMask
            val mChannelMaskField = AudioRecord::class.java.getDeclaredField("mChannelMask")
            mChannelMaskField.isAccessible = true
            mChannelMaskField[audioRecord] = channelMask

            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            val bufferSizeInBytes = minBufferSize * 8

            // audioRecord.audioBuffSizeCheck(bufferSizeInBytes)
            val audioBuffSizeCheckMethod =
                AudioRecord::class.java.getDeclaredMethod(
                    "audioBuffSizeCheck",
                    Int::class.javaPrimitiveType
                )
            audioBuffSizeCheckMethod.isAccessible = true
            audioBuffSizeCheckMethod.invoke(audioRecord, bufferSizeInBytes)

            val channelIndexMask = 0

            val sampleRateArray = intArrayOf(sampleRate)
            val session = intArrayOf(AudioManager.AUDIO_SESSION_ID_GENERATE)

            val initResult: Int
            if (Build.VERSION.SDK_INT < AndroidVersions.API_31_ANDROID_12) {
                // private native final int native_setup(Object audiorecord_this,
                // Object /*AudioAttributes*/ attributes,
                // int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
                // int buffSizeInBytes, int[] sessionId, String opPackageName,
                // long nativeRecordInJavaObj);
                val nativeSetupMethod = AudioRecord::class.java.getDeclaredMethod(
                    "native_setup",
                    Any::class.java,
                    Any::class.java,
                    IntArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    IntArray::class.java,
                    String::class.java,
                    Long::class.javaPrimitiveType
                )
                nativeSetupMethod.isAccessible = true
                initResult = nativeSetupMethod.invoke(
                    audioRecord,
                    WeakReference<AudioRecord>(audioRecord),
                    attributes,
                    sampleRateArray,
                    channelMask,
                    channelIndexMask,
                    audioRecord.audioFormat,
                    bufferSizeInBytes,
                    session,
                    FakeContext.get().getOpPackageName(),
                    0L
                ) as Int
            } else {
                // Assume `context` is never `null`
                val attributionSource: AttributionSource = FakeContext.get().getAttributionSource()

                // Assume `attributionSource.getPackageName()` is never null

                // ScopedParcelState attributionSourceState = attributionSource.asScopedParcelState()
                val asScopedParcelStateMethod =
                    AttributionSource::class.java.getDeclaredMethod("asScopedParcelState")
                asScopedParcelStateMethod.isAccessible = true

                val result = asScopedParcelStateMethod.invoke(attributionSource) as java.lang.AutoCloseable?
                result.use { attributionSourceState ->
                    val getParcelMethod: Method =
                        attributionSourceState!!.javaClass.getDeclaredMethod("getParcel")
                    val attributionSourceParcel =
                        getParcelMethod.invoke(attributionSourceState) as Parcel
                    if (Build.VERSION.SDK_INT < AndroidVersions.API_34_ANDROID_14) {
                        // private native int native_setup(Object audiorecordThis,
                        // Object /*AudioAttributes*/ attributes,
                        // int[] sampleRate, int channelMask, int channelIndexMask, int audioFormat,
                        // int buffSizeInBytes, int[] sessionId, @NonNull Parcel attributionSource,
                        // long nativeRecordInJavaObj, int maxSharedAudioHistoryMs);
                        val nativeSetupMethod = AudioRecord::class.java.getDeclaredMethod(
                            "native_setup",
                            Any::class.java,
                            Any::class.java,
                            IntArray::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            IntArray::class.java,
                            Parcel::class.java,
                            Long::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        nativeSetupMethod.isAccessible = true
                        initResult = nativeSetupMethod.invoke(
                            audioRecord,
                            WeakReference(audioRecord),
                            attributes,
                            sampleRateArray,
                            channelMask,
                            channelIndexMask,
                            audioRecord.audioFormat,
                            bufferSizeInBytes,
                            session,
                            attributionSourceParcel,
                            0L,
                            0
                        ) as Int
                    } else {
                        // Android 14 added a new int parameter "halInputFlags"
                        // <https://github.com/aosp-mirror/platform_frameworks_base/commit/f6135d75db79b1d48fad3a3b3080d37be20a2313>
                        val nativeSetupMethod = AudioRecord::class.java.getDeclaredMethod(
                            "native_setup",
                            Any::class.java,
                            Any::class.java,
                            IntArray::class.java,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            IntArray::class.java,
                            Parcel::class.java,
                            Long::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        nativeSetupMethod.isAccessible = true
                        initResult = nativeSetupMethod.invoke(
                            audioRecord,
                            WeakReference(audioRecord),
                            attributes,
                            sampleRateArray,
                            channelMask,
                            channelIndexMask,
                            audioRecord.audioFormat,
                            bufferSizeInBytes,
                            session,
                            attributionSourceParcel,
                            0L,
                            0,
                            0
                        ) as Int
                    }
                }
            }

            if (initResult != AudioRecord.SUCCESS) {
                Ln.e("Error code $initResult when initializing native AudioRecord object.")
                throw RuntimeException("Cannot create AudioRecord")
            }

            // mSampleRate = sampleRate[0]
            val mSampleRateField = AudioRecord::class.java.getDeclaredField("mSampleRate")
            mSampleRateField.isAccessible = true
            mSampleRateField[audioRecord] = sampleRateArray[0]

            // audioRecord.mSessionId = session[0]
            val mSessionIdField = AudioRecord::class.java.getDeclaredField("mSessionId")
            mSessionIdField.isAccessible = true
            mSessionIdField[audioRecord] = session[0]

            // audioRecord.mState = AudioRecord.STATE_INITIALIZED
            val mStateField = AudioRecord::class.java.getDeclaredField("mState")
            mStateField.isAccessible = true
            mStateField[audioRecord] = AudioRecord.STATE_INITIALIZED

            return audioRecord
        } catch (e: Exception) {
            Ln.e("Cannot create AudioRecord", e)
            throw AudioCaptureException()
        }
    }
}
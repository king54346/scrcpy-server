package com.genymobile.scrcpy.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.os.Build
import com.genymobile.scrcpy.AndroidVersions
import com.genymobile.scrcpy.FakeContext.Companion.get
import com.genymobile.scrcpy.audio.AudioConfig.createAudioFormat
import com.genymobile.scrcpy.util.Ln.e
import com.genymobile.scrcpy.util.Ln.w
import java.nio.ByteBuffer

class AudioPlaybackCapture(private val keepPlayingOnDevice: Boolean) : AudioCapture {
    private var recorder: AudioRecord? = null
    private var reader: AudioRecordReader? = null

    @SuppressLint("PrivateApi")
    @Throws(AudioCaptureException::class)
    private fun createAudioRecord(): AudioRecord {
        // See <https://github.com/Genymobile/scrcpy/issues/4380>
        try {
            val audioMixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val audioMixingRuleBuilderClass =
                Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")

            // AudioMixingRule.Builder audioMixingRuleBuilder = new AudioMixingRule.Builder();
            val audioMixingRuleBuilder = audioMixingRuleBuilderClass.getConstructor().newInstance()

            // audioMixingRuleBuilder.setTargetMixRole(AudioMixingRule.MIX_ROLE_PLAYERS);
            val mixRolePlayersConstant =
                audioMixingRuleClass.getField("MIX_ROLE_PLAYERS").getInt(null)
            val setTargetMixRoleMethod = audioMixingRuleBuilderClass.getMethod(
                "setTargetMixRole",
                Int::class.javaPrimitiveType
            )
            setTargetMixRoleMethod.invoke(audioMixingRuleBuilder, mixRolePlayersConstant)

            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

            // audioMixingRuleBuilder.addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE, attributes);
            val ruleMatchAttributeUsageConstant =
                audioMixingRuleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            val addMixRuleMethod = audioMixingRuleBuilderClass.getMethod(
                "addMixRule",
                Int::class.javaPrimitiveType,
                Any::class.java
            )
            addMixRuleMethod.invoke(
                audioMixingRuleBuilder,
                ruleMatchAttributeUsageConstant,
                attributes
            )

            // AudioMixingRule audioMixingRule = builder.build();
            val audioMixingRule =
                audioMixingRuleBuilderClass.getMethod("build").invoke(audioMixingRuleBuilder)

            // audioMixingRuleBuilder.voiceCommunicationCaptureAllowed(true);
            val voiceCommunicationCaptureAllowedMethod = audioMixingRuleBuilderClass.getMethod(
                "voiceCommunicationCaptureAllowed",
                Boolean::class.javaPrimitiveType
            )
            voiceCommunicationCaptureAllowedMethod.invoke(audioMixingRuleBuilder, true)

            val audioMixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val audioMixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")

            // AudioMix.Builder audioMixBuilder = new AudioMix.Builder(audioMixingRule);
            val audioMixBuilder = audioMixBuilderClass.getConstructor(audioMixingRuleClass)
                .newInstance(audioMixingRule)

            // audioMixBuilder.setFormat(createAudioFormat());
            val setFormat = audioMixBuilder.javaClass.getMethod(
                "setFormat",
                AudioFormat::class.java
            )
            setFormat.invoke(audioMixBuilder, createAudioFormat())

            val routeFlagName =
                if (keepPlayingOnDevice) "ROUTE_FLAG_LOOP_BACK_RENDER" else "ROUTE_FLAG_LOOP_BACK"
            val routeFlags = audioMixClass.getField(routeFlagName).getInt(null)

            // audioMixBuilder.setRouteFlags(routeFlag);
            val setRouteFlags = audioMixBuilder.javaClass.getMethod(
                "setRouteFlags",
                Int::class.javaPrimitiveType
            )
            setRouteFlags.invoke(audioMixBuilder, routeFlags)

            // AudioMix audioMix = audioMixBuilder.build();
            val audioMix = audioMixBuilderClass.getMethod("build").invoke(audioMixBuilder)

            val audioPolicyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val audioPolicyBuilderClass =
                Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

            // AudioPolicy.Builder audioPolicyBuilder = new AudioPolicy.Builder();
            val audioPolicyBuilder =
                audioPolicyBuilderClass.getConstructor(Context::class.java).newInstance(
                    get()
                )

            // audioPolicyBuilder.addMix(audioMix);
            val addMixMethod = audioPolicyBuilderClass.getMethod("addMix", audioMixClass)
            addMixMethod.invoke(audioPolicyBuilder, audioMix)

            // AudioPolicy audioPolicy = audioPolicyBuilder.build();
            val audioPolicy = audioPolicyBuilderClass.getMethod("build").invoke(audioPolicyBuilder)

            // AudioManager.registerAudioPolicyStatic(audioPolicy);
            val registerAudioPolicyStaticMethod = AudioManager::class.java.getDeclaredMethod(
                "registerAudioPolicyStatic",
                audioPolicyClass
            )
            registerAudioPolicyStaticMethod.isAccessible = true
            val result = registerAudioPolicyStaticMethod.invoke(null, audioPolicy) as Int
            if (result != 0) {
                throw RuntimeException("registerAudioPolicy() returned $result")
            }

            // audioPolicy.createAudioRecordSink(audioPolicy);
            val createAudioRecordSinkClass =
                audioPolicyClass.getMethod("createAudioRecordSink", audioMixClass)
            return createAudioRecordSinkClass.invoke(audioPolicy, audioMix) as AudioRecord
        } catch (e: Exception) {
            e("Could not capture audio playback", e)
            throw AudioCaptureException()
        }
    }

    @Throws(AudioCaptureException::class)
    override fun checkCompatibility() {
        if (Build.VERSION.SDK_INT < AndroidVersions.API_33_ANDROID_13) {
            w("Audio disabled: audio playback capture source not supported before Android 13")
            throw AudioCaptureException()
        }
    }

    @Throws(AudioCaptureException::class)
    override fun start() {
        recorder = createAudioRecord()
        recorder!!.startRecording()
        reader = AudioRecordReader(recorder!!)
    }

    override fun stop() {
        if (recorder != null) {
            // Will call .stop() if necessary, without throwing an IllegalStateException
            recorder!!.release()
        }
    }

    override fun read(outDirectBuffer: ByteBuffer, outBufferInfo: MediaCodec.BufferInfo): Int {
        return reader?.read(outDirectBuffer, outBufferInfo) ?: 0
    }
}
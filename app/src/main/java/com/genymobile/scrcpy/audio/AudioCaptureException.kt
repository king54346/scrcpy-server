package com.genymobile.scrcpy.audio

/**
 * Exception for any audio capture issue.
 *
 *
 * This includes the case where audio capture failed on Android 11 specifically because the running App (Shell) was not in foreground.
 *
 *
 * Its purpose is to disable audio without errors (that's why the exception is empty, any error message must be printed by the caller before
 * throwing the exception).
 */
class AudioCaptureException : Exception()
package com.genymobile.scrcpy.util

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Scanner

object IO {

    /**
     * 向文件描述符写入数据，自动处理中断信号
     *
     * @param fd 目标文件描述符
     * @param from 要写入的数据缓冲区
     * @return 实际写入的字节数
     * @throws IOException 当发生非EINTR错误时抛出
     */
    @Throws(IOException::class)
    private fun write(fd: FileDescriptor, from: ByteBuffer): Int {
        while (true) {
            try {
                return Os.write(fd, from)
            } catch (e: ErrnoException) {
                // 处理系统调用被信号中断的情况，重试写入操作
                if (e.errno != OsConstants.EINTR) {
                    throw IOException(e)
                }
                // 如果是EINTR错误，循环继续重试
            }
        }
    }

    /**
     * 确保完整写入缓冲区中的所有数据
     *
     * @param fd 目标文件描述符
     * @param from 要写入的数据缓冲区
     * @throws IOException 当写入过程中发生错误时抛出
     */
    @Throws(IOException::class)
    fun writeFully(fd: FileDescriptor, from: ByteBuffer) {
        // 循环写入直到缓冲区中的所有数据都被写入
        while (from.hasRemaining()) {
            write(fd, from)
        }
    }

    /**
     * 将字节数组的指定范围完整写入文件描述符
     *
     * @param fd 目标文件描述符
     * @param buffer 要写入的字节数组
     * @param offset 数组中的起始偏移量
     * @param len 要写入的字节长度
     * @throws IOException 当写入过程中发生错误时抛出
     */
    @Throws(IOException::class)
    fun writeFully(fd: FileDescriptor, buffer: ByteArray, offset: Int, len: Int) {
        // 使用ByteBuffer.wrap来避免创建额外的缓冲区副本
        writeFully(fd, ByteBuffer.wrap(buffer, offset, len))
    }

    /**
     * 将输入流的内容转换为字符串
     * 注意：此方法会消耗输入流，使用后流将不可再用
     *
     * @param inputStream 要读取的输入流
     * @return 输入流内容的字符串表示
     */
    fun toString(inputStream: InputStream): String {
        val builder = StringBuilder()
        val scanner = Scanner(inputStream).use { scanner ->
            // 使用use确保Scanner会被正确关闭
            while (scanner.hasNextLine()) {
                builder.append(scanner.nextLine()).append('\n')
            }
        }
        return builder.toString()
    }

    /**
     * 检查IOException是否由管道破裂引起
     * 这通常发生在读取端关闭而写入端仍在写入时
     *
     * @param e 要检查的IOException
     * @return 如果是管道破裂错误返回true，否则返回false
     */
    fun isBrokenPipe(e: IOException): Boolean {
        val cause = e.cause
        return cause is ErrnoException && cause.errno == OsConstants.EPIPE
    }

    /**
     * 检查Exception是否由管道破裂引起
     *
     * @param e 要检查的Exception
     * @return 如果是管道破裂引起的IOException返回true，否则返回false
     */
    fun isBrokenPipe(e: Exception?): Boolean {
        return e is IOException && isBrokenPipe(e)
    }
}
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

object NativeLibraryLoader {
    private const val TAG = "NativeLibraryLoader"
    private const val JAR_LIB_PATH = "/lib/arm64-v8a/"
    private const val TEMP_DIR = "/data/local/tmp/"

    // 内存缓存：记录已加载的库文件名，防止重复加载
    private val loadedLibraries = HashSet<String>()

    @Throws(IOException::class)
    fun loadLibraryFromJar(libName: String) {
        // 1. 先加载 libc++_shared.so (如果需要)
        // 使用 try-catch 包裹，因为有些环境可能不需要或者 jar 包里没有
        try {
            extractAndLoad("libc++_shared.so")
        } catch (e: Exception) {
            System.err.println("$TAG: Info: libc++_shared.so not loaded (might be missing or already loaded).")
        }

        // 2. 加载主业务库
        extractAndLoad("lib$libName.so")
    }

    /**
     * 核心方法：提取并加载库（带防重机制）
     */
    @Throws(IOException::class)
    private fun extractAndLoad(filename: String) {
        // 使用 synchronized 锁，防止多线程并发调用导致文件写入冲突
        synchronized(loadedLibraries) {
            // Check 1: 如果内存中记录已经加载过，直接返回
            if (loadedLibraries.contains(filename)) {
                // println("$TAG: $filename already loaded, skipping.")
                return
            }

            val tempFile = File(TEMP_DIR, filename)

            // Check 2: (可选) 如果文件已存在且大小不为0，是否跳过解压？
            // 建议：开发阶段最好每次都覆盖，防止 Jar 包更新了但 .so 还是旧的。
            // 这里我们选择：每次都覆盖提取，确保版本一致性。

            // --- 开始提取 ---
            val resourcePath = JAR_LIB_PATH + filename
            NativeLibraryLoader::class.java.getResourceAsStream(resourcePath).use { input ->
                if (input == null) {
                    throw FileNotFoundException("File $filename was not found inside JAR at $resourcePath")
                }

                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(4096)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }

            // --- 赋予权限 ---
            try {
                tempFile.setReadable(true, false)
                tempFile.setExecutable(true, false)
                tempFile.setWritable(true, false)
            } catch (ignored: Exception) { }

            // --- 加载库 ---
            try {
                System.load(tempFile.absolutePath)
                // 标记为已加载
                loadedLibraries.add(filename)
                println("$TAG: Loaded $filename from ${tempFile.absolutePath}")
            } catch (e: UnsatisfiedLinkError) {
                System.err.println("$TAG: Failed to load ${tempFile.absolutePath}")
                throw e
            }
        }
    }
    @Throws(IOException::class)
    fun loadJarResource(path: String): ByteArray {
        // 确保路径以 / 开头，代表从 Classpath 根目录查找
        val resourcePath = if (path.startsWith("/")) path else "/$path"

        val inputStream: InputStream? = NativeLibraryLoader::class.java.getResourceAsStream(resourcePath)

        if (inputStream == null) {
            throw FileNotFoundException("Resource not found inside JAR: $resourcePath")
        }

        return inputStream.use { it.readBytes() }
    }
}
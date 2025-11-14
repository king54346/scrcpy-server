Android 显示系统的两层架构

1. DisplayManager 层（较低层 - 硬件抽象层）
   DisplayManager
   ↓
   负责管理物理显示设备
   ↓
   Display 对象（代表物理显示器）
职责：
    检测硬件显示器的连接/断开
    管理显示器的物理属性（分辨率、刷新率等）
    提供显示器的基本信息

2. Window Manager (WM) 层级（较高层 - 窗口管理层）
   Window Manager
   ↓
   管理窗口和显示内容
   ↓
   DisplayContent 对象（WM 内部的显示器表示）

职责：
    管理每个显示器上的窗口
    处理窗口布局、层叠顺序
    管理显示器的配置（窗口模式、旋转等）
    协调多窗口、分屏等高级功能

AIDL 接口：
服务端需要去实现 Stub方法
public class MyDisplayWindowListener extends IDisplayWindowListener.Stub

客户端可以去调用 AIDL中实现的方法
系统方法会提供监听能力去调用服务端代码

AIDL 接口有两种实现方式：
方法 1: 使用 Stub 类（标准方式）
如果你有 .aidl 文件，编译后会生成包含 Stub 抽象类的 Java/Kotlin 文件。
public interface IMyService extends android.os.IInterface
{
/** Default implementation for IMyService. */
public static class Default implements com.malt.myservice.IMyService
public static abstract class Stub extends android.os.Binder implements com.malt.myservice.IMyService



方法 2: 手动实现（如果没有生成的 Stub 类）
如果系统 API 没有提供生成的类，需要手动实现 Binder
class ClipboardListener : Binder(), IOnPrimaryClipChangedListener

private val clipboardListener = object : IOnPrimaryClipChangedListener.Stub() {
override fun dispatchPrimaryClipChanged() {
handleClipboardChange()
}
}


**音频管道流程**:
麦克风/系统音频 → AudioCapture 
                       ↓
                   编码(AAC/Opus)
                       ↓
                   网络传输
                       ↓
                   客户端播放


**视频管道流程**:
屏幕/摄像头 → SurfaceCapture
                  ↓
              编码(H.264/H.265)
                  ↓
              网络传输
                  ↓
              客户端显示



androidx.room - SQLite 数据库抽象层
类型安全的数据库访问

androidx.media - 媒体播放
Media2, MediaRouter


androidx.work - 后台任务调度
WorkManager - 可延迟的后台任务

androidx.core - 核心工具类和扩展函数
ContextCompat, ViewCompat 等兼容性辅助类
Kotlin 扩展函数


androidx.core.graphics
图形和绘制相关：

ColorUtils - 颜色工具类（颜色转换、计算对比度等）
BitmapCompat - Bitmap 兼容
PathSegment - 路径片段
Insets - 边距
BlendModeCompat - 混合模式兼容
drawable 子包 - Drawable 相关工具

androidx.core.os
系统功能相关：

BuildCompat - 系统版本检查
BundleCompat - Bundle 兼容
HandlerCompat - Handler 兼容
LocaleListCompat - 语言列表兼容
TraceCompat - 性能追踪
CancellationSignal - 取消信号
UserManagerCompat - 用户管理


androidx.core.net
网络相关：
UriCompat - Uri 兼容
ConnectivityManagerCompat - 网络连接管理
MailTo - 邮件链接解析


音频同步： 1. rarRecorder 原始音频 2. audioEncoder 编码音频
1. rarRecorder 原始音频
```java
 // 音频捕捉：开启一个录音器，但是只有前台应用才能获取音频，创建一个 Intent(消息传递对象,用于Activity，Service，Broadcast通信)，用于启动一个HeapDumpActivity，让系统认为 shell 处于前台状态，app_process也是前台应用
    Intent intent = new Intent(Intent.ACTION_MAIN);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.addCategory(Intent.CATEGORY_LAUNCHER);
    intent.setComponent(new ComponentName(FakeContext.PACKAGE_NAME, "com.android.shell.HeapDumpActivity"));
    ServiceManager.getActivityManager().startActivity(intent);
    // 录音器 反射获取AudioRecord对象 并设置远程混音音频源,麦克风音频源 AudioSource的REMOTE_SUBMIX和MIC
    Constructor<AudioRecord> audioRecordConstructor = AudioRecord.class.getDeclaredConstructor(long.class);
    audioRecordConstructor.setAccessible(true);
    AudioRecord audioRecord = audioRecordConstructor.newInstance(0L);
    // 设置参数 + native_setup初始化
    Method nativeSetupMethod = AudioRecord.class.getDeclaredMethod("native_setup", Object.class, Object.class, int[].class, int.class, int.class, int.class, int.class, int[].class, String.class, long.class);
    nativeSetupMethod.setAccessible(true);
    initResult = (int) nativeSetupMethod.invoke(audioRecord, new WeakReference<>(audioRecord), attributes, sampleRateArray, channelMask, channelIndexMask, audioRecord.getAudioFormat(), bufferSizeInBytes, session, FakeContext.get().getOpPackageName(), 0L);
```
2. audioEncoder 编码音频
```java
    // audioEncoder编码音频   输入缓冲区可用(编码器内部的输入缓冲区有空闲) ---> 记录index到inputTask队列中 ---> 编码 ----> 输出缓冲区可用(getOutputBuffer)----> index和编码结果写入output队列中
    mediaCodec = createMediaCodec(codec, encoderName);
    // inputTask队列中获取index
    InputTask task = inputTasks.take();
    // 返回一个用于写入原始数据编码的ByteBuffer 对象
    ByteBuffer buffer = mediaCodec.getInputBuffer(task.index);
    // 录音器读取到buffer并设置bufferinfo
    int r = AudioRecord.read(buffer, bufferInfo);
    if (r <= 0) {
        throw new IOException("Could not read audio: " + r);
    }
    // 提交输入缓冲区 
    mediaCodec.queueInputBuffer(task.index, bufferInfo.offset, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags);

    //outputTasks队列中获取bufferInfo
    OutputTask task = outputTasks.take();

    //处理输出数据，写入到流中    
    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
    streamer.writePacket(buffer, task.bufferInfo);
```

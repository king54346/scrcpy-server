app_process可以在android上启动一个独立java进程

1. 使用adb shell 启动java进程，此时java进程拥有shell级别的权限(远程控制和远程录屏)
2. 利用app启动java进程，此时java进程和app权限相同




1. 编译 Java 代码 dx --dex --output=MyApp.dex MyApp.class
2. 将文件推送到设备  adb push MyApp.dex /data/local/tmp/
3. shell 执行 app_process:
    adb shell app_process <path_to_executable> <class_name> [options]
    <path_to_executable> 是 Android 设备上运行应用程序的路径，通常是 /system/bin
    <class_name> 是你想执行的 Java 类


app_process 因为没有manifest，@RequiresPermission无法通过，也只就是没有调用api的权限，需要通过反射来调用方法

android 虚拟显示: 可以使用 DisplayManager 和 VirtualDisplay
```
    // 获取屏幕信息
    ScreenInfo screenInfo = device.getScreenInfo();

   
    // 反射获取DisplayManagerGlobal调用createVirtualDisplay 或者使用getSystemService https://android.googlesource.com/platform/frameworks/base.git/+/pie-release-2/core/java/android/hardware/display/DisplayManagerGlobal.java

    DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
     //创建虚拟显示
    displayManager.createVirtualDisplay(name,height,width,dpi,surface,// 渲染到的 Surface)

    // 开一个线程处理编码使用Android内置的mediacodec实现
    MediaCodec mediaCodec = createMediaCodec(codec, encoderName);
    // 输入的Surface(传入给上面VirtualDisplay渲染)，并对其进行编码
    surface = mediaCodec.createInputSurface();
    mediaCodec.start();


    // 处理编码后的数据
    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
    int outputBufferId = mediaCodec.dequeueOutputBuffer(bufferInfo, timeoutUs);
    if (outputBufferId >= 0) {
        ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferId);
        // 处理编码后的输出，如写入文件或推送视频流
        mediaCodec.releaseOutputBuffer(outputBufferId, false);
    }
```

音频同步： 1. rarRecorder 原始音频 2. audioEncoder 编码音频
```
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

输入： 反射调用injectInputEvent向系统注入输入事件（如触摸、按键等）https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/hardware/input/InputManager.java

```

  injectInputEventMethod = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
  // event KeyEvent，MotionEvent(多点触控，触控压力，滑动)   mode： 0异步处理，1 等待事件处理，2 处理结果完成例如
  method.invoke(manager, event, mode);

  // 字符串输入
  // 字符串转换为字符数组并获取对应的键事件
  KeyEvent[] events = charMap.getEvents(chars);
  //循环注入每个键事件
  for (KeyEvent event : events) {
    if (!device.injectEvent(event, Device.INJECT_MODE_ASYNC)) {
        return false;
    }
  }

```

粘贴板： 反射调用 clipboard, android.content.IClipboard

复制文本到粘贴板

```
// 创建 ClipData 对象，表示要复制的数据
String textToCopy = "This is the text to copy";
ClipData clip = ClipData.newPlainText("label", textToCopy);
// 设置为粘贴板的主要内容
clipboard.setPrimaryClip(clip);
//获取剪贴板的内容
ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
// 提取文本内容
String pastedText = item.getText().toString();
```


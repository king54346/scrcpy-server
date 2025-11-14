/*
 *  这个接口用于监听显示器窗口容器的变化，属于 Android 系统框架的一部分。
 *  与Android 系统服务和应用之间建立通信桥梁
 ┌─────────────────────────────────────────────────────────────┐
 │                      系统服务进程                              │
 │                  (system_server)                             │
 │                                                              │
 │  ┌──────────────────────────────────────┐                   │
 │  │   WindowManagerService               │                   │
 │  │                                      │                   │
 │  │   List<IDisplayWindowListener>       │                   │
 │  │   mDisplayWindowListeners = [...]    │                   │
 │  │                                      │                   │
 │  │   void notifyDisplayAdded(int id) {  │                   │
 │  │     for(listener : mListeners) {     │                   │
 │  │       listener.onDisplayAdded(id) ───┼───────────┐       │
 │  │     }                                │           │       │
 │  │   }                                  │           │       │
 │  └──────────────────────────────────────┘           │       │
 └──────────────────────────────────────────────────────┼───────┘
                                                        │
                                         Binder IPC     │
                                         (跨进程通信)    │
                                                        │
 ┌──────────────────────────────────────────────────────┼───────┐
 │                      你的应用进程                      │       │
 │                                                      │       │
 │  ┌──────────────────────────────────────┐           │       │
 │  │  IDisplayWindowListener.Stub         │           │       │
 │  │  (Binder 对象)                        │           │       │
 │  │                                      │           │       │
 │  │  onTransact(code, data, reply) { ◄───┼───────────┘       │
 │  │    switch(code) {                    │                   │
 │  │      case TRANSACTION_onDisplayAdded:│                   │
 │  │        int id = data.readInt()       │                   │
 │  │        this.onDisplayAdded(id) ──────┼────┐              │
 │  │    }                                 │    │              │
 │  │  }                                   │    │              │
 │  └──────────────────────────────────────┘    │              │
 │                                              │              │
 │  ┌──────────────────────────────────────┐    │              │
 │  │  MyDisplayWindowListener             │    │              │
 │  │                                      │    │              │
 │  │  override fun onDisplayAdded(id) { ◄─┼────┘              │
 │  │    Log.d("Display added: $id")       │                   │
 │  │    // 你的业务逻辑                     │                   │
 │  │  }                                   │                   │
 │  └──────────────────────────────────────┘                   │
 └─────────────────────────────────────────────────────────────┘
 */

package android.view;

import android.content.res.Configuration;

/**
 * Interface to listen for changes to display window-containers.
 *
 * This differs from DisplayManager's DisplayListener in a couple ways:
 *  - onDisplayAdded is always called after the display is actually added to the WM hierarchy.
 *    This corresponds to the DisplayContent and not the raw Dislay from DisplayManager.
 *  - onDisplayConfigurationChanged is called for all configuration changes, not just changes
 *    to displayinfo (eg. windowing-mode).
 *
 */
oneway interface IDisplayWindowListener {

    /**
     * Called when a new display is added to the WM hierarchy. The existing display ids are returned
     * when this listener is registered with WM via {@link #registerDisplayWindowListener}.
     当新显示器添加到 Window Manager 层级时调用
     displayId: 新添加显示器的 ID

     */
    void onDisplayAdded(int displayId);

    /**
     * 当显示器的窗口容器配置改变时调用
       displayId: 发生变化的显示器 ID
       newConfig: 新的配置对象（使用 in 表示参数从调用方传入）
       监听所有配置变化，不仅仅是显示信息变化（例如窗口模式变化
       监听 Configuration 对象的所有变化：
          newConfig.orientation          // 屏幕方向
          newConfig.screenLayout         // 屏幕布局（大小、长宽比）
          newConfig.uiMode              // UI 模式（夜间模式、车载模式等）
          newConfig.windowConfiguration  // 窗口配置（窗口模式、边界等）
          newConfig.densityDpi          // 密度
     */
    void onDisplayConfigurationChanged(int displayId, in Configuration newConfig);

    /**
     * 当显示器从层级中移除时调用
       displayId: 被移除显示器的 ID
     */
    void onDisplayRemoved(int displayId);
}
